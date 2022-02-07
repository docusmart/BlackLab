import uuid
from typing import *
import time
import requests
import os
from requests import Response, PreparedRequest

BLACKLAB_USER = os.getenv('BLACKLAB_USER', 'user')
BLACKLAB_PASSWORD = os.getenv("BLACKLAB_PASSWORD", '')
BLACKLAB_HOST = os.getenv("BLACKLAB_HOST", "http://localhost:6080")
TEST_DATA_ROOT = os.getenv("TEST_DATA_ROOT", ".")

if BLACKLAB_PASSWORD == '':
    print("missing blacklab password.")

def _create_request_id() -> str:
    return uuid.uuid4().hex

def _call_blacklab(blacklab_host: str,
                   headers: Dict[str, str],
                   path: str,
                   body: Optional[Dict[str, Any]] = None,
                   params: Dict[str, Any] = {},
                   files: Optional[Dict[str, Any]] = None,
                   timeout: int = 60 * 3,
                   method: str = "POST") -> Tuple[Optional[Response], float]:
    url = f"{blacklab_host}/{path}"

    def _create_def_headers():
        headers = {
            "X-Request-ID": _create_request_id(),
        }
        return headers

    all_headers = _create_def_headers()
    all_headers.update(headers)
    try:
        start = time.time() * 1000
        resp = requests.request(
            method,
            auth=requests.auth.HTTPBasicAuth(BLACKLAB_USER, BLACKLAB_PASSWORD),
            url=url,
            headers=all_headers,
            params=params,
            data=body,
            files=files,
            timeout=timeout)
        total_time = time.time() * 1000 - start
        #print(pretty_print_request(resp.request))
        return resp, total_time
    except Exception as ex:
        print(ex)
        return None, 0.0


def test_add_input():
    url_path='input-formats'
    file_spec = ("test-input-format.blf.yml", open(f"{TEST_DATA_ROOT}/test-input-format.blf.yml", 'rb'), 'text/vnd.yaml')
    results, latency = _call_blacklab(BLACKLAB_HOST, {}, url_path, files={"data": file_spec})
    assert results is not None
    print(results.text)
    assert results.ok


def test_create_index():
    index_name = f'{BLACKLAB_USER}:test-index'
    url_path = ''
    params = {
        'name': index_name,
        'display': index_name,
        'format': f'{BLACKLAB_USER}:test-input-format'
    }
    results, latency = _call_blacklab(BLACKLAB_HOST, {}, url_path, params=params)
    assert results is not None
    print(results.text)
    assert results.ok

def test_add_to_index():
    test_create_index()
    index_name = f'{BLACKLAB_USER}:test-index'
    documest_to_index = 'documents-to-index.xml'
    url_path=f'{index_name}/docs'
    file_spec = ('input.xml', open(f"{TEST_DATA_ROOT}/{documest_to_index}", 'rb'), 'text/xml')
    results, latency = _call_blacklab(BLACKLAB_HOST, {}, url_path, files={"data": file_spec})
    assert results is not None
    print(results.text)
    assert results.ok

    read_index, latency = _call_blacklab(BLACKLAB_HOST, {}, url_path, method='GET')
    assert read_index is not None
    print(read_index.text)
    assert read_index.ok







