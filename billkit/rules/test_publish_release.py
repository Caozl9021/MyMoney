import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

import publish_release as publisher


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'releases').mkdir()
        self.artifact = self.root / 'artifact'
        self.artifact.mkdir()
        self.name = 'MyMoney-rules-1.3.0.mymoney-rules.zip'
        self.content = b'validated-test-archive'
        digest = hashlib.sha256(self.content).hexdigest()
        (self.artifact / self.name).write_bytes(self.content)
        self.checksum = (digest + '  ' + self.name + '\n').encode()
        (self.artifact / (self.name + '.sha256')).write_bytes(self.checksum)
        (self.root / 'package.json').write_text(json.dumps({'packageVersion': '1.3.0'}))
        (self.root / 'releases/1.3.0.json').write_text(json.dumps({'version': '1.3.0', 'sha256': digest}))
        (self.root / 'releases/1.3.0.md').write_text('Public release notes')
        self.commit = 'a' * 40
        self.release = {'body': 'Public release notes', 'name': '规则包 1.3.0 · 微信凭证识别更新', 'id': 1, 'tag_name': 'rules-v1.3.0', 'draft': True,
                        'prerelease': False, 'upload_url': 'https://uploads.github.com/test{?name}',
                        'html_url': 'https://github.com/owner/repo/releases/tag/rules-v1.3.0'}
        self.assets = []
        self.stored = {}
        self.exists = False
        self.mutations = []
        self.addCleanup(patch.stopall)
        patch.object(publisher, 'ROOT', self.root).start()
        patch.object(publisher, 'request', side_effect=self.request).start()

    def request(self, url, token, method='GET', data=None, content_type=None, accept=None):
        if method != 'GET':
            self.mutations.append((url, method, data))
        if '/git/ref/tags/' in url:
            result = {'object': {'type': 'commit', 'sha': self.commit}}
        elif '/releases/tags/' in url:
            if not self.exists:
                raise urllib.error.HTTPError(url, 404, 'not found', {}, None)
            result = self.release
        elif '/releases?per_page=100&page=' in url:
            result = [self.release] if self.exists else []
        elif url.endswith('/releases') and method == 'POST':
            payload = json.loads(data)
            self.assertTrue(payload['draft'])
            self.assertEqual(self.commit, payload['target_commitish'])
            self.assertEqual('Public release notes', payload['body'])
            self.exists = True
            result = self.release
        elif '/assets?per_page=' in url:
            result = self.assets
        elif url.startswith('https://uploads.github.com/'):
            from urllib.parse import parse_qs, urlsplit
            name = parse_qs(urlsplit(url).query)['name'][0]
            asset_url = 'https://api.github.com/asset/' + name
            result = {'name': name, 'url': asset_url}
            self.assets.append(result)
            self.stored[asset_url] = data
        elif '/asset/' in url:
            self.assertEqual('application/octet-stream', accept)
            return self.stored[url]
        elif url.endswith('/releases/1') and method == 'PATCH':
            self.assertEqual(2, len(self.assets))
            self.assertEqual('false', json.loads(data)['make_latest'])
            self.release['draft'] = False
            result = self.release
        else:
            self.fail('Unexpected request ' + method + ' ' + url)
        return json.dumps(result).encode()

    def publish(self):
        publisher.publish(self.artifact, 'not-a-real-token', 'owner/repo', 'rules-v1.3.0', self.commit)

    def test_create_upload_verify_publish_and_idempotent_rerun(self):
        self.publish()
        self.assertFalse(self.release['draft'])
        self.assertEqual(4, len(self.mutations))
        self.mutations.clear()
        self.publish()
        self.assertEqual([], self.mutations)

    def test_existing_different_attachment_is_never_replaced(self):
        self.publish()
        self.mutations.clear()
        self.stored[self.assets[0]['url']] = b'other archive'
        with self.assertRaisesRegex(ValueError, 'content differs'):
            self.publish()
        self.assertEqual([], self.mutations)

    def test_wrong_approved_digest_stops_before_network(self):
        (self.artifact / self.name).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'approved release'):
            self.publish()
        publisher.request.assert_not_called()

    def test_wrong_remote_commit_stops_before_mutation(self):
        with self.assertRaisesRegex(ValueError, 'another commit'):
            publisher.publish(self.artifact, 'test', 'owner/repo', 'rules-v1.3.0', 'b' * 40)
        self.assertEqual([], self.mutations)

    def test_incomplete_published_release_is_not_modified(self):
        self.exists = True
        self.release['draft'] = False
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.publish()
        self.assertEqual([], self.mutations)

    def test_existing_draft_with_unapproved_notes_is_never_published(self):
        self.exists = True
        self.release['body'] = 'Unapproved internal notes'
        with self.assertRaisesRegex(ValueError, 'metadata differs'):
            self.publish()
        self.assertEqual([], self.mutations)

    def test_existing_prerelease_is_not_modified(self):
        self.exists = True
        self.release['prerelease'] = True
        with self.assertRaisesRegex(ValueError, 'metadata differs'):
            self.publish()
        self.assertEqual([], self.mutations)

    def test_cross_host_redirect_strips_authorization(self):
        req = urllib.request.Request('https://api.github.com/asset', headers={'Authorization': 'Bearer test'})
        redirected = publisher.SafeRedirect().redirect_request(
            req, None, 302, 'Found', {}, 'https://release-assets.githubusercontent.com/file')
        self.assertIsNone(redirected.get_header('Authorization'))


if __name__ == '__main__':
    unittest.main()
