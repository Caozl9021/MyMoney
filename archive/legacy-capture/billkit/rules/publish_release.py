#!/usr/bin/env python3
"""Publish an already validated rules artifact using the CI-scoped GitHub token."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if urllib.parse.urlsplit(newurl).scheme != 'https':
            raise ValueError('Insecure redirect')
        if urllib.parse.urlsplit(req.full_url).hostname != urllib.parse.urlsplit(newurl).hostname:
            redirected.remove_header('Authorization')
        return redirected


def request(url, token, method='GET', data=None, content_type='application/json',
            accept='application/vnd.github+json'):
    req = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': 'Bearer ' + token,
        'Accept': accept,
        'X-GitHub-Api-Version': '2022-11-28',
        'Content-Type': content_type,
        'User-Agent': 'MyMoney-rule-release',
    })
    with urllib.request.build_opener(SafeRedirect()).open(req, timeout=90) as response:
        return response.read()


def publish(directory, token, repository, tag, commit):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository')
    version = json.loads((ROOT / 'package.json').read_text())['packageVersion']
    if tag != 'rules-v' + version or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Tag/version/commit mismatch')
    approved = json.loads((ROOT / 'releases' / (version + '.json')).read_text())
    notes = (ROOT / 'releases' / (version + '.md')).read_text()
    title = '规则包 ' + version + ' · 微信凭证识别更新'
    name = 'MyMoney-rules-' + version + '.mymoney-rules.zip'
    archive = (directory / name).read_bytes()
    digest = hashlib.sha256(archive).hexdigest()
    if approved['version'] != version or approved['sha256'] != digest:
        raise ValueError('Artifact differs from approved release')
    checksum = (directory / (name + '.sha256')).read_bytes()
    if checksum != (digest + '  ' + name + '\n').encode():
        raise ValueError('Invalid checksum attachment')
    attachments = {name: archive, name + '.sha256': checksum}
    api = 'https://api.github.com/repos/' + repository

    def call(path, method='GET', payload=None):
        return json.loads(request(api + path, token, method,
                                  json.dumps(payload).encode() if payload is not None else None))

    # Resolve the tag itself, including annotated tags, before accepting an existing release.
    target = call('/git/ref/tags/' + urllib.parse.quote(tag, safe=''))['object']
    for _ in range(8):
        if target['type'] == 'commit':
            break
        if target['type'] != 'tag':
            raise ValueError('Release tag does not resolve to a commit')
        target = call('/git/tags/' + target['sha'])['object']
    if target['type'] != 'commit' or target['sha'] != commit:
        raise ValueError('Remote tag points to another commit')
    try:
        release = call('/releases/tags/' + urllib.parse.quote(tag, safe=''))
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        error.close()
        # The tag endpoint may omit drafts. Search authenticated release listings before creating.
        release = None
        for page in range(1, 21):
            listed = call('/releases?per_page=100&page=' + str(page))
            matches = [item for item in listed if item['tag_name'] == tag]
            if len(matches) > 1:
                raise ValueError('Ambiguous releases for tag')
            if matches:
                release = matches[0]
                break
            if len(listed) < 100:
                break
        else:
            raise ValueError('Release listing exceeded search limit')
        if release is None:
            release = call('/releases', 'POST', {
                'tag_name': tag, 'target_commitish': commit,
                'name': title, 'body': notes, 'draft': True, 'prerelease': False,
            })
    if (release['tag_name'] != tag or release.get('body') != notes or
            release.get('name') != title or release['prerelease']):
        raise ValueError('Existing release metadata differs from approved publication')
    # Do not silently mutate a previously published release or replace different attachments.
    assets = call('/releases/' + str(release['id']) + '/assets?per_page=100')
    if len(assets) >= 100 or len({a['name'] for a in assets}) != len(assets):
        raise ValueError('Ambiguous existing release assets')
    if set(a['name'] for a in assets) - attachments.keys():
        raise ValueError('Unexpected release attachment')
    by_name = {a['name']: a for a in assets}
    if not release['draft'] and set(by_name) != set(attachments):
        raise ValueError('Published release is incomplete; refusing to modify it')
    for filename, content in attachments.items():
        if filename in by_name:
            existing = request(by_name[filename]['url'], token,
                               accept='application/octet-stream')
            if existing != content:
                raise ValueError('Existing attachment content differs: ' + filename)
            continue
        upload = release['upload_url'].split('{', 1)[0]
        asset = json.loads(request(upload + '?name=' + urllib.parse.quote(filename, safe=''), token,
                'POST', content, 'application/zip' if filename == name else 'text/plain'))
        if request(asset['url'], token, accept='application/octet-stream') != content:
            raise ValueError('Uploaded attachment verification failed: ' + filename)
    if release['draft']:
        release = call('/releases/' + str(release['id']), 'PATCH', {
            'draft': False, 'prerelease': False, 'make_latest': 'false',
        })
    if release['draft'] or release['prerelease']:
        raise ValueError('Release was not published')
    print(release['html_url'])
    print('SHA256 ' + digest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifact-dir', required=True, type=Path)
    args = parser.parse_args()
    publish(args.artifact_dir, os.environ['GITHUB_TOKEN'], os.environ['GITHUB_REPOSITORY'],
            os.environ['GITHUB_REF_NAME'], os.environ['GITHUB_SHA'])
