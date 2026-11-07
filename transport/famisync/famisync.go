// Package famisync is the production embedded-tsnet transport for MyMoney's family
// rule-sync and ledger-backup APIs. It exposes gomobile-friendly functions that the
// Android FamilyTransport calls over the JNI bridge. It is deliberately stricter than
// the throwaway probe:
//
//   - Destination is an explicit per-call allowlist: exactly one approved host:port
//     and a path prefix, supplied by the app from the latest approved peer mapping.
//     The SAME parsed URL is used for the policy check and for dialing (no check-then-
//     swap window). tsnet.Server.Dial falls back to the OS network for non-tailnet
//     IPs, so gating here is mandatory.
//   - Redirects are never followed.
//   - Request and response bodies are bounded; large uploads stream from a file and
//     large downloads stream to a .part file, never buffered whole on the JNI bridge.
//   - Every request carries a context deadline (connection + overall budget).
//
// Node lifecycle: Start() brings up one tsnet node persisted in stateDir; Stop()
// closes it but keeps state so the next Start() reuses the same identity. Only one
// node runs at a time (the Android side enforces single-instance/refcount too).
package famisync

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

// Response body caps. Rule sync JSON is small; GET responses are bounded here and
// again on the Kotlin side. Downloads use a caller-supplied cap.
const (
	maxResponseBody   = 1 << 20   // 1 MiB, rule-sync JSON
	maxUploadBody     = 200 << 20 // 200 MiB, ledger backup
	maxDownloadBody   = 200 << 20 // 200 MiB, ledger backup
	defaultDialBudget = 15 * time.Second
	defaultReadBudget = 120 * time.Second
)

type node struct {
	mu  sync.Mutex
	srv *tsnet.Server
	dir string
	log *os.File
	ip  string
}

var (
	cur  *node
	curM sync.Mutex
)

// opRegistry tracks in-flight cancelable operations so the Android side can cancel
// a single request by its operationId without touching the shared tsnet node or
// other concurrent operations (AA3). Each entry owns exactly one cancel func; the
// registry is only touched under opM for very short critical sections, never
// across network IO.
var (
	opM      sync.Mutex
	opCancel = map[string]context.CancelFunc{}
)

// OpHandle is the gomobile-exported handle returned by NewOpHandle. It carries an
// opaque operationId that the caller passes to GetToFileOp and to CancelOp. It
// deliberately exposes no key/token, only an app-generated random id.
type OpHandle struct {
	id string
}

// NewOpHandle allocates a registry slot for operationId (an app-random string) and
// returns a handle to pass to the download and cancel calls. Calling twice with the
// same operationId reuses the slot idempotently. The slot is emptied by the download
// when it finishes (finally), and also by CancelOp.
func NewOpHandle(operationId string) (*OpHandle, error) {
	if strings.TrimSpace(operationId) == "" {
		return nil, errors.New("operationId required")
	}
	opM.Lock()
	if _, ok := opCancel[operationId]; !ok {
		opCancel[operationId] = nil // reserve slot; download installs its own cancel
	}
	opM.Unlock()
	return &OpHandle{id: operationId}, nil
}

// ID returns the opaque operation id (safe to log; no credentials).
func (h *OpHandle) ID() string {
	if h == nil {
		return ""
	}
	return h.id
}

// registerCancel installs the cancel func for this operation. Returns false if the
// operation was already cancelled before it started (cancel-before-start race), so
// the caller must abort immediately rather than begin a blocking request.
func registerCancel(operationId string, cancel context.CancelFunc) bool {
	opM.Lock()
	defer opM.Unlock()
	if _, ok := opCancel[operationId]; !ok {
		// Slot gone (never created or already removed) — treat as pre-cancelled.
		cancel()
		return false
	}
	if opCancel[operationId] != nil {
		// Already has a cancel (double register) — cancel the new one, keep old.
		cancel()
		return true
	}
	opCancel[operationId] = cancel
	return true
}

// unregisterCancel clears the slot, so a late finally from an old op cannot delete
// a newer op's slot. Idempotent.
func unregisterCancel(operationId string) {
	opM.Lock()
	delete(opCancel, operationId)
	opM.Unlock()
}

// CancelOp cancels the in-flight operation if any. Idempotent and safe when the
// operation is unknown or already finished. Returns JSON {"ok":true,"found":bool}.
// It only signals cancellation; it never blocks on the download's IO or lock.
// If the slot exists but has no registered func yet (cancel-before-start), it
// removes the slot so a later registerCancel sees it gone and aborts.
func CancelOp(operationId string) string {
	opM.Lock()
	cancel, exists := opCancel[operationId]
	if exists {
		delete(opCancel, operationId) // pre-start or post-cancel: refuse future register
	}
	opM.Unlock()
	found := cancel != nil
	if found {
		cancel()
	}
	return `{"ok":true,"found":` + boolStr(found) + `}`
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

// Start brings up the tsnet node. stateDir is an app-private noBackup directory;
// authKey may be empty when the node is already registered (persisted state).
// ctrlURL may be empty to use the default control server. Returns the node's
// tailnet IPv4 once Up() succeeds. It does NOT prove the home API is reachable.
func Start(stateDir, hostname, authKey, ctrlURL string) (string, error) {
	return StartWithTimeout(stateDir, hostname, authKey, ctrlURL, 20000)
}

// StartWithTimeout owns the native deadline. Returning an error means the server
// has been closed; no detached Java Future may later publish a live node.
func StartWithTimeout(stateDir, hostname, authKey, ctrlURL string, timeoutMs int) (string, error) {
	if timeoutMs <= 0 || timeoutMs > 20000 {
		timeoutMs = 20000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	curM.Lock()
	defer curM.Unlock()
	if cur != nil && cur.srv != nil {
		return cur.ip, nil // already running; caller reuses
	}
	n := &node{dir: stateDir}
	if err := os.MkdirAll(filepath.Join(stateDir, "logs"), 0o700); err != nil {
		return "", fmt.Errorf("mkdir stateDir: %w", err)
	}
	logsDir := filepath.Join(stateDir, "tslog")
	if err := os.MkdirAll(logsDir, 0o700); err != nil {
		return "", fmt.Errorf("mkdir logsDir: %w", err)
	}
	// Android: logpolicy.LogsDir() has no android case and otherwise fails under an
	// app UID, so pin TS_LOGS_DIR/TMPDIR to writable app-private dirs and disable
	// upstream log upload. (Documented in the U2 probe.)
	os.Setenv("TS_LOGS_DIR", logsDir)
	os.Setenv("TMPDIR", stateDir)
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	lf, err := os.OpenFile(filepath.Join(stateDir, "logs", "tsnet.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return "", fmt.Errorf("open log: %w", err)
	}
	n.log = lf

	srv := &tsnet.Server{
		Dir:        filepath.Join(stateDir, "tsnet"),
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: ctrlURL,
		Logf: func(format string, args ...any) {
			// Redacted: never log auth keys, login URLs, or bearer tokens. tsnet's
			// own Logf can contain control-plane chatter; we keep it in the private
			// file only and mirror a short line to logcat.
			fmt.Fprintln(lf, fmt.Sprintf(format, args...))
		},
		UserLogf: func(format string, args ...any) {
			fmt.Fprintln(lf, "USER "+fmt.Sprintf(format, args...))
		},
	}
	if err := srv.Start(); err != nil {
		lf.Close()
		return "", fmt.Errorf("tsnet.Start: %w", err)
	}
	if _, err := srv.Up(ctx); err != nil {
		srv.Close()
		lf.Close()
		return "", fmt.Errorf("tsnet.Up: %w", err)
	}
	ip4, _ := srv.TailscaleIPs()
	n.srv = srv
	n.ip = ip4.String()
	fmt.Fprintf(lf, "UP ip4=%v host=%s\n", ip4, hostname)
	cur = n
	return n.ip, nil
}

// policy is the per-request destination allowlist. All fields required.
// Field order matters for the positional literals below: scheme, host, port,
// pathPrefix. (A mismatch here silently shifted every value and made every
// request fail with "destination not allowed: :" — U5 regression.)
type policy struct {
	scheme     string
	host       string
	port       string
	pathPrefix string
}

// buildURL parses rawPath against the run's base and enforces the allowlist on the
// exact URL that will be dialed. rawPath must be a path (with optional query),
// e.g. "/v1/sync/snapshot" — never an absolute URL — so the host can't be swapped.
func (p policy) buildURL(rawPath string) (*url.URL, error) {
	if !strings.HasPrefix(rawPath, "/") {
		return nil, fmt.Errorf("path must start with '/': %q", rawPath)
	}
	u, err := url.Parse(p.scheme + "://" + net.JoinHostPort(p.host, p.port) + rawPath)
	if err != nil {
		return nil, fmt.Errorf("bad path: %w", err)
	}
	if u.User != nil || u.Fragment != "" {
		return nil, errors.New("url must not contain userinfo or fragment")
	}
	if !strings.EqualFold(u.Hostname(), p.host) || u.Port() != p.port {
		return nil, fmt.Errorf("destination not allowed: %s:%s", u.Hostname(), u.Port())
	}
	if p.scheme == "https" && u.Scheme != "https" {
		return nil, errors.New("scheme mismatch")
	}
	if !strings.HasPrefix(u.Path, p.pathPrefix) {
		return nil, fmt.Errorf("path not allowed: %s", u.Path)
	}
	return u, nil
}

// The pinned tsnet UserDial may fall back to the OS while the peer map is
// still arriving after Up. Resolve once through its plan, wait for a tailnet
// route, then call its netstack TCP function DIRECTLY. Do not re-enter UserDial:
// a route change between planning and dialing must never select the OS network.
// Sys is version-specific; re-audit this adapter on every tailscale update.
func dialTailnetOnly(ctx context.Context, network, addr string,
	plan func(context.Context, string, string) (netip.AddrPort, bool, error),
	dial func(context.Context, netip.AddrPort) (net.Conn, error)) (net.Conn, error) {
	if network != "tcp" && network != "tcp4" && network != "tcp6" {
		return nil, fmt.Errorf("family transport requires TCP: %s", network)
	}
	if dial == nil {
		return nil, errors.New("tailnet TCP stack unavailable")
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil, fmt.Errorf("family peer not ready: %w", err)
		}
		ip, viaTailnet, err := plan(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		if viaTailnet {
			return dial(ctx, ip)
		}
		timer := time.NewTimer(100 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, fmt.Errorf("family peer not ready: %w", ctx.Err())
		case <-timer.C:
		}
	}
}

var (
	clientOnce   sync.Once
	sharedClient *http.Client
)

// dialNode resolves the current tsnet node on every dial, so a Stop/Start cycle
// picks up the new node without rebuilding the client.
func dialNode(ctx context.Context, network, addr string) (net.Conn, error) {
	curM.Lock()
	n := cur
	curM.Unlock()
	if n == nil || n.srv == nil {
		return nil, errors.New("tsnet node not started")
	}
	d := n.srv.Sys().Dialer.Get()
	return dialTailnetOnly(ctx, network, addr, d.UserDialPlan, d.NetstackDialTCP)
}

// client returns ONE shared client. Re-dialing the tsnet netstack is the expensive
// part of every call (pair/snapshot/push/pull/ack), so connections are kept alive
// and reused per host instead of being torn down after each response.
func client() *http.Client {
	clientOnce.Do(func() {
		sharedClient = &http.Client{
			Transport: &http.Transport{
				DialContext:         dialNode,
				MaxIdleConns:        8,
				MaxIdleConnsPerHost: 4,
				IdleConnTimeout:     90 * time.Second,
				// No proxy: the dialer must be the tsnet node, not the OS.
				Proxy: nil,
			},
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}
	})
	return sharedClient
}

// closeIdleConnections drops pooled connections whose netstack is going away.
func closeIdleConnections() {
	if sharedClient != nil {
		sharedClient.CloseIdleConnections()
	}
}

// Get performs a bounded GET to rawPath under the allowlist. It returns a JSON
// object {"status":<int>,"body":"<string>"} (gomobile allows at most one return
// value besides error). A non-2xx still returns its body in the object; transport
// failures come back as a Go error.
func Get(scheme, host, port, pathPrefix, rawPath, headersJSON string, readBudgetMs int) (string, error) {
	status, body, err := getImpl(scheme, host, port, pathPrefix, rawPath, headersJSON, readBudgetMs)
	if err != nil {
		return "", err
	}
	return jsonResult(map[string]any{"status": status, "body": body})
}

func getImpl(scheme, host, port, pathPrefix, rawPath, headersJSON string, readBudgetMs int) (int, string, error) {
	u, err := policy{scheme, host, port, pathPrefix}.buildURL(rawPath)
	if err != nil {
		return 0, "", err
	}
	headers, err := parseHeaders(headersJSON)
	if err != nil {
		return 0, "", err
	}
	if readBudgetMs <= 0 {
		readBudgetMs = int(defaultReadBudget / time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(readBudgetMs)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return 0, "", fmt.Errorf("new request: %w", err)
	}
	applyHeaders(req, headers)
	resp, err := client().Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("do: %w", err)
	}
	defer resp.Body.Close()
	body, err := readBounded(resp.Body, maxResponseBody)
	if err != nil {
		return resp.StatusCode, "", err
	}
	return resp.StatusCode, string(body), nil
}

// PutFile streams bodyFile to rawPath with the given HTTP method (PUT or POST) and
// returns a JSON object {"status":<int>,"body":"<string>"}. The family service uses
// POST for /v1/pair and /v1/sync/push and PUT for /v1/backups/{id}, so the method is
// part of the request instead of being hardcoded. If expectSha is non-empty the
// streamed bytes are hashed and verified locally; a mismatch returns an error.
func PutFile(method, scheme, host, port, pathPrefix, rawPath, headersJSON, bodyFile, expectSha string) (string, error) {
	status, body, err := putFileImpl(method, scheme, host, port, pathPrefix, rawPath, headersJSON, bodyFile, expectSha)
	if err != nil {
		return "", err
	}
	return jsonResult(map[string]any{"status": status, "body": body})
}

func putFileImpl(method, scheme, host, port, pathPrefix, rawPath, headersJSON, bodyFile, expectSha string) (int, string, error) {
	m := strings.ToUpper(strings.TrimSpace(method))
	if m != http.MethodPut && m != http.MethodPost {
		return 0, "", fmt.Errorf("method not allowed: %q", method)
	}
	u, err := policy{scheme, host, port, pathPrefix}.buildURL(rawPath)
	if err != nil {
		return 0, "", err
	}
	headers, err := parseHeaders(headersJSON)
	if err != nil {
		return 0, "", err
	}
	f, err := os.Open(bodyFile)
	if err != nil {
		return 0, "", fmt.Errorf("open body: %w", err)
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return 0, "", fmt.Errorf("stat body: %w", err)
	}
	if st.Size() < 1 || st.Size() > maxUploadBody {
		return 0, "", fmt.Errorf("body size out of range: %d", st.Size())
	}
	h := sha256.New()
	body := io.TeeReader(f, h)
	ctx, cancel := context.WithTimeout(context.Background(), defaultReadBudget)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, m, u.String(), body)
	if err != nil {
		return 0, "", fmt.Errorf("new request: %w", err)
	}
	req.ContentLength = st.Size()
	applyHeaders(req, headers)
	resp, err := client().Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("do: %w", err)
	}
	defer resp.Body.Close()
	respBody, _ := readBounded(resp.Body, 64<<10)
	sum := hex.EncodeToString(h.Sum(nil))
	if expectSha != "" && !strings.EqualFold(sum, expectSha) {
		return resp.StatusCode, string(respBody), fmt.Errorf("local sha mismatch: got %s want %s", sum, expectSha)
	}
	return resp.StatusCode, string(respBody), nil
}

// GetToFile streams a GET response to destPath (via a .part file renamed on success)
// and returns a JSON object {"status":<int>,"bytes":<int64>}. Verifies length against
// expectLen (>=0) and SHA against expectSha (if non-empty); maxBytes caps the size.
// On any failure the .part file is removed and destPath is never created.
func GetToFile(scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha string, expectLen, maxBytes, readBudgetMs int) (string, error) {
	status, n, err := getToFileImpl(scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha, expectLen, maxBytes, readBudgetMs)
	if err != nil {
		return "", err
	}
	return jsonResult(map[string]any{"status": status, "bytes": n})
}

func getToFileImpl(scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha string, expectLen, maxBytes, readBudgetMs int) (int, int64, error) {
	u, err := policy{scheme, host, port, pathPrefix}.buildURL(rawPath)
	if err != nil {
		return 0, 0, err
	}
	headers, err := parseHeaders(headersJSON)
	if err != nil {
		return 0, 0, err
	}
	if maxBytes <= 0 || maxBytes > maxDownloadBody {
		maxBytes = maxDownloadBody
	}
	if readBudgetMs <= 0 {
		readBudgetMs = int(defaultReadBudget / time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(readBudgetMs)*time.Millisecond)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return 0, 0, fmt.Errorf("new request: %w", err)
	}
	applyHeaders(req, headers)
	resp, err := client().Do(req)
	if err != nil {
		return 0, 0, fmt.Errorf("do: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		b, _ := readBounded(resp.Body, 4096)
		return resp.StatusCode, 0, fmt.Errorf("status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	if expectLen >= 0 && resp.ContentLength >= 0 && resp.ContentLength != int64(expectLen) {
		return resp.StatusCode, 0, fmt.Errorf("content-length mismatch: got %d want %d", resp.ContentLength, expectLen)
	}
	part := destPath + ".part"
	_ = os.MkdirAll(filepath.Dir(destPath), 0o700)
	out, err := os.Create(part)
	if err != nil {
		return resp.StatusCode, 0, fmt.Errorf("create part: %w", err)
	}
	h := sha256.New()
	limited := io.LimitReader(resp.Body, int64(maxBytes)+1)
	written, copyErr := io.Copy(io.MultiWriter(out, h), limited)
	syncErr := out.Sync()
	out.Close()
	if copyErr != nil {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("copy: %w", copyErr)
	}
	if syncErr != nil {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("sync: %w", syncErr)
	}
	if written > int64(maxBytes) {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("download exceeds cap %d", maxBytes)
	}
	if expectLen >= 0 && written != int64(expectLen) {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("length mismatch: wrote %d want %d", written, expectLen)
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if expectSha != "" && !strings.EqualFold(sum, expectSha) {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("sha mismatch: got %s want %s", sum, expectSha)
	}
	if err := os.Rename(part, destPath); err != nil {
		os.Remove(part)
		return resp.StatusCode, 0, fmt.Errorf("rename: %w", err)
	}
	return resp.StatusCode, written, nil
}

// ProgressCallback is implemented by the Android side and passed to GetToFileOp.
// OnProgress is invoked from the Go download goroutine with the monotonically
// increasing received byte count; the Kotlin side must marshal to the main thread.
// It must return quickly and never block or call back into the transport.
type ProgressCallback interface {
	OnProgress(received int64)
}

// GetToFileOp is the AA3 cancelable, progress-reporting variant of GetToFile. It
// registers operationId's cancel in the registry before starting the (blocking)
// request, honours cancel-before-start, streams to a unique .part and verifies
// length/SHA before the atomic rename. A cancelled operation returns a Go error
// whose text starts with "cancelled:" so the Kotlin layer can map it to a
// CancellationException without mislabelling it as an auth failure.
func GetToFileOp(operationId, scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha string, expectLen, maxBytes, readBudgetMs int, cb ProgressCallback) (string, error) {
	res, err := getToFileOpImpl(operationId, scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha, expectLen, maxBytes, readBudgetMs, cb)
	if err != nil {
		return "", err
	}
	return res, nil
}

func getToFileOpImpl(operationId, scheme, host, port, pathPrefix, rawPath, headersJSON, destPath, expectSha string, expectLen, maxBytes, readBudgetMs int, cb ProgressCallback) (string, error) {
	u, err := policy{scheme, host, port, pathPrefix}.buildURL(rawPath)
	if err != nil {
		return "", err
	}
	headers, err := parseHeaders(headersJSON)
	if err != nil {
		return "", err
	}
	if maxBytes <= 0 || maxBytes > maxDownloadBody {
		maxBytes = maxDownloadBody
	}
	if readBudgetMs <= 0 {
		readBudgetMs = int(defaultReadBudget / time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(readBudgetMs)*time.Millisecond)
	// Register BEFORE the blocking request so cancel-before-start is honoured. The
	// registry cancel wraps our deadline cancel; unregister only if still ours.
	registered := registerCancel(operationId, cancel)
	defer func() {
		cancel()
		unregisterCancel(operationId)
	}()
	if !registered {
		return "", fmt.Errorf("cancelled: before start")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return "", fmt.Errorf("new request: %w", err)
	}
	applyHeaders(req, headers)
	resp, err := client().Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return "", fmt.Errorf("cancelled: %w", ctx.Err())
		}
		return "", fmt.Errorf("do: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		b, _ := readBounded(resp.Body, 4096)
		return "", fmt.Errorf("status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	if expectLen >= 0 && resp.ContentLength >= 0 && resp.ContentLength != int64(expectLen) {
		return "", fmt.Errorf("content-length mismatch: got %d want %d", resp.ContentLength, expectLen)
	}
	part := destPath + ".part"
	_ = os.MkdirAll(filepath.Dir(destPath), 0o700)
	out, err := os.Create(part)
	if err != nil {
		return "", fmt.Errorf("create part: %w", err)
	}
	h := sha256.New()
	limited := io.LimitReader(resp.Body, int64(maxBytes)+1)
	// Wrap the reader so each chunk reports progress. CopyBuffer with an explicit
	// buffer is used so we control chunking; progress fires at least per chunk and
	// the Kotlin side throttles to 200ms.
	var written int64
	progressReader := &progressReader{r: limited, cb: cb, received: &written}
	buf := make([]byte, 64*1024)
	_, copyErr := io.CopyBuffer(io.MultiWriter(out, h), progressReader, buf)
	syncErr := out.Sync()
	out.Close()
	if copyErr != nil {
		os.Remove(part)
		if ctx.Err() != nil {
			return "", fmt.Errorf("cancelled: %w", ctx.Err())
		}
		return "", fmt.Errorf("copy: %w", copyErr)
	}
	if syncErr != nil {
		os.Remove(part)
		return "", fmt.Errorf("sync: %w", syncErr)
	}
	if written > int64(maxBytes) {
		os.Remove(part)
		return "", fmt.Errorf("download exceeds cap %d", maxBytes)
	}
	if expectLen >= 0 && written != int64(expectLen) {
		os.Remove(part)
		return "", fmt.Errorf("length mismatch: wrote %d want %d", written, expectLen)
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if expectSha != "" && !strings.EqualFold(sum, expectSha) {
		os.Remove(part)
		return "", fmt.Errorf("sha mismatch: got %s want %s", sum, expectSha)
	}
	if err := os.Rename(part, destPath); err != nil {
		os.Remove(part)
		return "", fmt.Errorf("rename: %w", err)
	}
	return jsonResult(map[string]any{"status": resp.StatusCode, "bytes": written})
}

// progressReader reports cumulative bytes to cb after each Read. It is not safe for
// concurrent use, but each download owns its own instance on one goroutine.
type progressReader struct {
	r        io.Reader
	cb       ProgressCallback
	received *int64
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	if n > 0 {
		*p.received += int64(n)
		if p.cb != nil {
			p.cb.OnProgress(*p.received)
		}
	}
	return n, err
}

// Status returns the node IP or "down".
func Status() string {
	curM.Lock()
	n := cur
	curM.Unlock()
	if n == nil || n.srv == nil {
		return "down"
	}
	return "up " + n.ip
}

// Stop closes the tsnet server but KEEPS persisted state, so a later Start()
// reuses the same node identity without re-auth.
func Stop() error {
	curM.Lock()
	n := cur
	cur = nil
	curM.Unlock()
	closeIdleConnections()
	if n == nil {
		return nil
	}
	if n.srv != nil {
		n.srv.Close()
	}
	if n.log != nil {
		n.log.Close()
	}
	return nil
}

// Sha256File returns the hex SHA-256 of a local file.
func Sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func jsonResult(v map[string]any) (string, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func readBounded(r io.Reader, limit int) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, int64(limit)+1))
	if err != nil {
		return nil, err
	}
	if len(b) > limit {
		return nil, fmt.Errorf("response exceeds %d bytes", limit)
	}
	return b, nil
}

func applyHeaders(req *http.Request, headers map[string]string) {
	for k, v := range headers {
		req.Header.Set(k, v)
	}
}

func parseHeaders(headersJSON string) (map[string]string, error) {
	if strings.TrimSpace(headersJSON) == "" {
		return map[string]string{}, nil
	}
	var m map[string]string
	if err := json.Unmarshal([]byte(headersJSON), &m); err != nil {
		return nil, fmt.Errorf("headers json: %w", err)
	}
	return m, nil
}
