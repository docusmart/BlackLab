import csv
import datetime
import hashlib
import itertools
import json
import os
import pathlib
import string
import subprocess
import sys
import time
import urllib.parse
import uuid
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed, wait, Future
from typing import *
from argparse import ArgumentParser
import requests
from requests import Response, PreparedRequest
import random
import statistics
import pandas as pd

DRY_RUN = False
BL_USER = 'docuser'
BL_PASSWORD = os.getenv("BLACKLAB_PASSWORD", '')

if BL_PASSWORD == '':
    print("missing blacklab password.")


def log(message: str, muted: bool = False, end: str = "\n", file=sys.stderr):
    if muted:
        return
    print(message, end=end, flush=True, file=file)


def exec_cmd(cmd: List[str], env=None, wd=os.path.abspath('.'), dry_run=DRY_RUN) -> Optional[List[str]]:
    if env is None:
        env = os.environ.copy()

    cmd_str = ' '.join(cmd)
    output = []

    if dry_run:
        print(f'Will run command: {cmd_str} with env {env}')
        return None

    print(f'Executing command: {cmd_str}')
    process = subprocess.Popen(cmd, bufsize=1, env=env, stdout=subprocess.PIPE, universal_newlines=True, cwd=wd)
    if process.stdout is not None:
        for line in iter(process.stdout.readline, ''):
            output.append(line)

    return output


def pretty_print_request(req: PreparedRequest) -> str:
    headers_names = {'Authorization', 'X-Request-ID', 'X-Query-ID', 'X-Doc-Count', 'Content-Type', 'Content-Length'}
    rep = f'curl $BLACKLAB_HOST{req.path_url} '
    assert req.method is not None
    rep += f'-X {req.method.upper()} '
    for n, v in req.headers.items():
        if n in headers_names:
            rep += f'-H "{n}:{v}" '
    rep += f'-d "{str(req.body)}" '
    return rep


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
            auth=requests.auth.HTTPBasicAuth(BL_USER, BL_PASSWORD),
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


def _warm_up_blacklab(blacklab_host: str, index_name: str, url_path: str, headers: Dict[str, str],
                      all_patterns: Dict[str, str]) -> None:
    log(f"Warming up blacklab: {blacklab_host}")
    resp, total_time = _call_blacklab(blacklab_host, headers, '', method="GET")
    assert resp is not None and resp.ok, f'Blacklab is not running, can not hit path {blacklab_host}'

    warmup_size = min(len(all_patterns), 10)
    patts = dict(list(all_patterns.items())[:warmup_size])
    test_patterns(blacklab_host, patts, headers, url_path, {}, False)


def _create_request_id() -> str:
    return uuid.uuid4().hex


class _QueryRunResult:
    def __init__(self, results: Dict[str, float]):
        self.results = results

    def topk(self, k: int = 10) -> List[Tuple[str, float]]:
        items = list(self.results.items())
        items.sort(key=lambda x: x[1], reverse=True)
        return items[0:min(len(items) - 1, k)]

    def topk_pos(self, k: int = 10) -> Dict[str, int]:
        topk_dict = {}
        pos = 0
        for r_id, _ in self.topk(k):
            topk_dict[r_id] = pos
            pos += 1
        return topk_dict


class _WatchDogTestResults:
    def __init__(self):
        self.bl_query_res: List[_QueryRunResult] = []
        self.inf_successful: List[float] = []
        self.failures: float = 0
        self.completed: int = 0
        self.incomplete: int = 0

    def add_query_results(self, result: _QueryRunResult):
        self.bl_query_res.append(result)

    def add_inf_results(self, results: List[float]):
        self.inf_successful.extend(results)

    def print_slow_rules_summary(self, k=5):
        rules_times: Dict[str, List[float]] = defaultdict(list)
        for res in self.bl_query_res:
            for rule, res_times in res.results.items():
                rules_times[rule].append(res_times)

        rules_avg = {}
        all_times = []
        for rule, res_rule_times in rules_times.items():
            all_times.extend(res_rule_times)
            rules_avg[rule] = statistics.mean(res_rule_times)

        df = pd.DataFrame(all_times)
        print("\nSummary: RULE running time")
        print(df.describe())

        print(f"\nAvg times for slowest {k} rules")
        for rule, avg_time in sorted(list(rules_avg.items()), key=lambda x: x[1], reverse=True)[0:k]:
            print(f"{rule} => {avg_time} ms")
        print("---------------------------------")

    def print_common_slow_rules(self, k=5):
        print("\nSlowest, most common queries and their positions")
        slow_results: List[Dict[str, int]] = [r.topk_pos(k) for r in self.bl_query_res]
        all_slow_rules = set()
        for rules_pos in slow_results:
            all_slow_rules.update(rules_pos.keys())

        rules_and_pos: Dict[str, List[int]] = {}
        for rule in all_slow_rules:
            positions = []
            for res in slow_results:
                if rule in res:
                    positions.append(res[rule])
            rules_and_pos[rule] = positions

        for rule, pos in sorted(list(rules_and_pos.items()), key=lambda x: len(x[1]), reverse=True):
            print(f"{rule} => {pos}")

    def print_summary_information(self):
        print("---------------------------------")
        print("Summary: INFERENCE result calls summary:")
        df = pd.DataFrame(self.inf_successful)
        print(df.describe())
        print(f"Successful: {len(self.inf_successful)}, Failures: {self.failures}")
        print("---------------------------------")

        self.print_slow_rules_summary(k=10)
        self.print_common_slow_rules(k=5)

    def to_json(self, out_file=sys.stdout):
        query_dicts = [r.results for r in self.bl_query_res]
        json_dict = {}
        json_dict['queryRuns'] = query_dicts
        json_dict['success'] = self.inf_successful
        json_dict['failures'] = self.failures
        json_dict['completed'] = self.completed
        json_dict['incomplete'] = self.incomplete
        json_str = json.dumps(json_dict, indent=2)
        out_file.write(json_str)

    @classmethod
    def from_json(cls, json_file):
        with open(json_file, 'r') as fp:
            json_res = json.load(fp)
        test_res = _WatchDogTestResults()
        query_dicts = json_res['queryRuns']
        test_res.bl_query_res = [_QueryRunResult(r) for r in query_dicts]
        test_res.inf_successful = json_res['success']
        test_res.failures = json_res['failures']
        test_res.completed = json_res['completed']
        test_res.incomplete = json_res['incomplete']

        return test_res


def watch_dog_log_analysis(log_json: str):
    tr = _WatchDogTestResults.from_json(log_json)
    tr.print_summary_information()


def read_tomcat_logs(file_loc: str) -> _QueryRunResult:
    collector: Dict[str, float] = {}
    with open(file_loc, "r") as log_file:
        hits_lines = [l for l in log_file.readlines() if "/hits" in l]

    for line in hits_lines:
        parts = line.split(' ')
        rule_id = parts[2]
        res_time = float(parts[11])
        collector[rule_id] = res_time
    return _QueryRunResult(collector)


def watchdog_run(blacklab_host: str, ann_host: str, doc_sha1: str, concurrent_reqs: int, redis_container: Optional[str],
                 tomcat_log_file: Optional[str], repeat: int) -> None:
    assert blacklab_host != '', 'blacklab host needs to be set'
    assert ann_host != '', 'ann host needs to be set'
    #aviato doc
    #doc_sha1 = "a3de7b64ff7b198e619fdbac91f8949a80b6f05e"
    inference_payload = {
        "doc_sha1":
        doc_sha1,
        "agreement_type":
        "nda",
        "class_names": [
            "NdaType", "IsAmendment", "TermType", "Coterminous", "SecondaryTermType", "SecondaryCoterminous",
            "TableTermType", "RenewalType", "SecondaryRenewalType", "TableRenewalType"
        ],
        "entity_names": [
            "GoverningLaw", "TermEndDate", "SecondaryTermEndDate", "TableTermEndDate", "RenewalTypeAutomaticCheckbox",
            "RenewalTypeManualCheckbox", "RenewalTypeAutomatic", "RenewalCancellationNotice",
            "TableRenewalCancellationNotice", "EffectiveDate", "TableEffectiveDate", "SignatureDate", "TermLength",
            "TableTermLength", "SecondaryTermLength", "NumberRenewalPeriods", "SecondaryNumberRenewalPeriods",
            "RenewalTermLength", "TableRenewalTermLength", "SecondaryRenewalTermLength", "Party", "TableParty",
            "SignatureParty", "Title", "TableTitle"
        ],
    }

    succ = []
    failure = []
    url_inf = f'{ann_host}/with-project/8bd8a52e-dc77-4149-a305-0aab8aadbee3/public/inference/'
    ## if running via nginx
    #url_inf = f'{ann_host}/api/public/inference/'
    thread_pool = ThreadPoolExecutor(max_workers=concurrent_reqs * 5, thread_name_prefix='ReqExec')

    def exec_request(num_req: int, batch: int):
        log(f"Starting request: {batch}, {num_req}")
        start = time.time() * 1000
        res: Response = requests.post(url_inf, json=inference_payload)
        #res: Response = requests.post(url_inf, json=inference_payload, timeout=30)
        tot_time = time.time() * 1000 - start
        if res.ok:
            log(f'Finished {tot_time} s')
            succ.append(tot_time)
        else:
            failure.append(res)
            log('F', end='')
            log(res.text)

    all_done: List[Future] = []
    all_not_done: List[Future] = []
    test_results = _WatchDogTestResults()

    log(f"Starting inference calls with concurrency={concurrent_reqs} and repeat={repeat}")
    for i in range(0, repeat):
        log("---------")
        if redis_container is not None:
            log('Flushing the redis cache')
            exec_cmd(f"docker exec -i -t {redis_container} redis-cli flushall".split(' '))

        if tomcat_log_file is not None:
            log(f"Truncating log file: {tomcat_log_file}")
            with open(tomcat_log_file, 'w') as f:
                f.truncate(0)

        should_clear_bl_cache = False
        if should_clear_bl_cache:
            log("Clearning blacklab cache")
            _call_blacklab(blacklab_host, {}, 'cache-clear')

        log(f"Starting batch {i} of requests")
        futures = [thread_pool.submit(exec_request, j, i) for j in range(concurrent_reqs)]
        log(f'Waiting on batch {i}')
        done, not_done = wait(futures, timeout=60 * 20 * concurrent_reqs)  # 20 mins per request
        all_done.extend(done)
        all_not_done.extend(not_done)

        if tomcat_log_file is not None:
            log(f"Collecting query information from tomcat log file: {tomcat_log_file}")
            test_results.add_query_results(read_tomcat_logs(tomcat_log_file))

    log(f'Completed: {len(all_done)}, Not Completed: {len(all_not_done)}')
    log(f'Sucesses {len(succ)}, failures: {len(failure)}')

    test_results.add_inf_results(succ)
    test_results.completed = len(all_done)
    test_results.incomplete = len(all_not_done)
    test_results.failures = len(failure)

    if tomcat_log_file:
        now = datetime.datetime.now().strftime("%Y%m%d%H%M")
        out_file = f'rule-requests-summary-{now}.json'
        with open(out_file, 'w') as outf:
            log(f"saving data from tests to: {out_file}")
            test_results.to_json(out_file=outf)
    log("Summary of successes")
    df = pd.DataFrame(succ)
    print(df.describe())


class _TestResponse:
    def __init__(self, http_res: Response, total_time: float):
        self.http_res = http_res
        self.total_time = total_time
        self.ok = http_res.ok


class TestResult:
    def __init__(self, patt_id: str, pattern: str):
        self.pattern = pattern
        self.pattern_id = patt_id
        self.all_responses: List[_TestResponse] = []

    def has_success(self) -> bool:
        succ = [r for r in self.all_responses if r is not None and r.ok]
        return len(succ) > 0

    def add_result(self, response: Optional[Response], total_time: float):
        if response is None:
            return
        self.all_responses.append(_TestResponse(response, total_time))

    def get_a_failure(self) -> Optional[Response]:
        failed = [r for r in self.all_responses if r is None or not r.ok]
        present = [r for r in failed if r is not None]
        if len(present) > 0:
            return present[0].http_res
        else:
            return None

    def avg_call_time(self) -> float:
        succ = [r.total_time for r in self.all_responses if r is not None and r.ok]
        if len(succ) == 0:
            log(f"No success can't calculate call time for: {self.pattern_id}")
            return -1
        return round(statistics.mean(succ), 2)

    def response_size(self) -> float:
        succ = [len(r.http_res.content) for r in self.all_responses if r is not None and r.ok]
        if len(succ) == 0:
            log(f"No success can't calculate response size for: {self.pattern_id}")
            return -1
        return int(statistics.mean(succ))

    def get_a_success(self) -> Optional[Response]:
        succ = [r for r in self.all_responses if r is not None and r.ok]
        if len(succ) == 0:
            return None
        return succ[0].http_res


def test_patterns(blacklab_host: str,
                  all_patterns: Dict[str, str],
                  headers: Dict[str, Any],
                  url_path: str,
                  patt_to_id: Dict[str, str],
                  store_res: bool,
                  repeat: int = 1,
                  file_prefix: str = '') -> List[TestResult]:
    i: int = 0
    all_responses: List[TestResult] = []
    for patt, rule_id in all_patterns.items():
        body = {"patt": patt}
        test_responses = TestResult(patt_to_id.get(patt, ''), patt)
        for t_repeat in range(repeat):
            # clear the bl cache
            _call_blacklab(blacklab_host, {}, 'cache-clear')
            req_headers = dict(headers)
            req_headers.update({'X-Query-ID': rule_id, 'X-Ann-Rule-ID': rule_id})
            resp, total_time = _call_blacklab(blacklab_host, req_headers, url_path, body)
            test_responses.add_result(resp, total_time)
        all_responses.append(test_responses)
        if test_responses.has_success():
            log(".", end="")
            succ_res = test_responses.get_a_success()
            if store_res and succ_res is not None:
                with open(f'out.{file_prefix}.{rule_id}.xml', 'w') as outf:
                    outf.write(succ_res.text)
        else:
            failed_res = test_responses.get_a_failure()
            status_code = failed_res.status_code if failed_res is not None else '-'
            log(f"F({status_code})")
        i += 1

    log("")
    return all_responses


def search_perf_test(blacklab_host: str,
                     index_name: str,
                     all_patterns_db: Dict[str, str],
                     filter: Optional[str],
                     store_bl_responses: bool,
                     repeat: int,
                     debug: bool = False,
                     bl_res_file_prefix: str = '') -> Tuple[List[TestResult], float]:
    """
    For a number requests call blacklab and capture times
    :return: list of tests results and mean response time of all successes
    """
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
    }

    assert len(all_patterns_db) > 0, "Can not run integ test with no patterns"

    url_path = f"docuser:{index_name}/hits"

    # warm up the jvm by running through the rules once
    #_warm_up_blacklab(blacklab_host, index_name, url_path, headers, all_patterns_db)

    # Now perform some measurements
    filtered_patterns = all_patterns_db
    if filter is not None:
        filter_ids = set(filter.split(","))
        filtered_patterns = {patt: r_id for patt, r_id in all_patterns_db.items() if r_id in filter_ids}

    log(f"Performing measurements  on {len(filtered_patterns)} queries, {repeat} times each")
    results = test_patterns(
        blacklab_host,
        filtered_patterns,
        headers,
        url_path,
        all_patterns_db,
        store_bl_responses,
        repeat=repeat,
        file_prefix=bl_res_file_prefix)
    success = [x for x in results if x.has_success()]
    failures = [x for x in results if not x.has_success()]

    log(f"Success: {len(success)}, Failures: {len(failures)}")
    all_success = []
    for r in sorted(success, key=lambda x: x.pattern_id):
        all_success.extend([x.total_time for x in r.all_responses if x.ok])
        log(f"{r.pattern_id} {r.avg_call_time()} ms {r.response_size()} bytes", file=sys.stdout)
    df = pd.DataFrame(all_success)
    log(str(df.describe()))

    if debug:
        log("Errors")
        res: Response
        failed_rules = []
        for result in failures:
            failed_rules.append(all_patterns_db.get(result.pattern, ''))
            if result.get_a_failure() is not None:
                failure_res = result.get_a_failure()
                fail_text = failure_res.text if failure_res is not None else ''
                log(f"{all_patterns_db.get(result.pattern, '')} {fail_text}")
        log(",".join(failed_rules))
    return results, round(float(df.mean()), 2)


def search_perf_test_multiple(current_blacklab_host: str,
                              new_blacklab_host: str,
                              index_name: str,
                              all_patterns_db: Dict[str, str],
                              filter: Optional[str],
                              store_bl_responses: bool,
                              repeat: int,
                              url_encoded_pats: bool = True,
                              debug: bool = False) -> None:
    log(f"Measuring current BL: {current_blacklab_host}")
    current_index_name, new_index_name = index_name, index_name
    if ',' in index_name:
        parts = index_name.split(',')
        current_index_name, new_index_name = parts[0], parts[1]

    current_bl_res, mean_current_bl_res = search_perf_test(
        current_blacklab_host,
        current_index_name,
        all_patterns_db,
        filter,
        store_bl_responses,
        repeat,
        debug=debug,
        bl_res_file_prefix='current-bl')
    log(f"\nMeasuring in new BL: {new_blacklab_host}")
    new_bl_tests_res, mean_new_bl_res = search_perf_test(
        new_blacklab_host,
        new_index_name,
        all_patterns_db,
        filter,
        store_bl_responses,
        repeat,
        debug=debug,
        bl_res_file_prefix='new-bl')
    new_bl_res: Dict[str, TestResult] = {r.pattern_id: r for r in new_bl_tests_res}

    all_res: Dict[str, List[float]] = {}
    for curr_res in current_bl_res:
        new_res = new_bl_res[curr_res.pattern_id]
        time_delta = new_res.avg_call_time() - curr_res.avg_call_time()
        time_pct = round(time_delta * 100 / curr_res.avg_call_time(), 2)
        size_delta = round(new_res.response_size() - curr_res.response_size(), 2)
        all_res[curr_res.pattern_id] = [
            curr_res.avg_call_time(),
            curr_res.response_size(),
            new_res.avg_call_time(),
            new_res.response_size(), time_pct, size_delta
        ]

    print("")
    print("Pattern, CurrTime, CurrSize, NewTime, NewSize, DeltaTimePct, DeltaSize")
    for patt, num_res in sorted(all_res.items(), key=lambda x: x[1][5]):
        print(f"{patt}, {', '.join([str(n) for n in num_res])}")

    print(f"Mean all successes")
    print(f"{mean_current_bl_res}, {mean_new_bl_res}, {round(mean_new_bl_res - mean_current_bl_res, 2)}")


def _read_input_file(file_name: str, url_encoded_queries: bool = True) -> Dict[str, str]:
    all_patterns_db = dict()
    csvfile = sys.stdin if file_name == "-" else open(file_name)
    csv_reader = csv.DictReader(csvfile)
    for row in csv_reader:
        pattern = urllib.parse.unquote_plus(row['pattern']) if url_encoded_queries else row['pattern']
        all_patterns_db[pattern] = row['ruleId']
    csvfile.close()
    return all_patterns_db


if __name__ == "__main__":
    argument_parser = ArgumentParser()
    argument_parser.add_argument("-o", "--blacklab-host", required=False, dest='host', help="blacklab host")
    argument_parser.add_argument("--ann-host", required=False, dest='ann_host', help="ann host", default=None)
    argument_parser.add_argument("-i", "--index", required=False, help="index name")
    argument_parser.add_argument("--dry-run", required=False, dest='dryrun', action='store_true')
    argument_parser.add_argument(
        "-t",
        "--type",
        required=True,
        type=str,
        dest='test_type',
        action='store',
        help='type of test: either laod or integ')
    argument_parser.add_argument(
        "-r",
        "--rules-db",
        required=False,
        type=str,
        dest='req_db',
        action='store',
        help='path to the csv with queries/patterns')
    argument_parser.add_argument(
        "-g", "--debug", required=False, dest='debug', action='store_true', default=False, help='enable debug output')
    argument_parser.add_argument(
        "--rule-ids",
        required=False,
        dest='rule_ids',
        type=str,
        default=None,
        help='comma separated ids of rules to run')
    argument_parser.add_argument(
        "--num-reps",
        required=False,
        dest='num_reps',
        type=int,
        default=3,
        help='number of reps to excercise a single query')
    argument_parser.add_argument(
        "--concurrency",
        required=False,
        dest='concurrency',
        type=int,
        default=5,
        help='set the concurrent requests for test that are concurrent')
    argument_parser.add_argument(
        "--redis-container-name",
        required=False,
        dest='redis_container',
        type=str,
        default=None,
        help='flushes the redis cache if set')
    argument_parser.add_argument(
        "--tomcat-logs",
        required=False,
        dest='tomcat_log_file',
        type=str,
        default=None,
        help='flushes the redis cache if set')
    argument_parser.add_argument(
        "--save-bl-res",
        required=False,
        dest='save_bl_res',
        action='store_true',
        default=False,
        help='saves blacklab responses')

    arguments = argument_parser.parse_args()
    blacklab_host = arguments.host
    index_name = arguments.index
    dry_run = arguments.dryrun
    repeat = arguments.num_reps
    rule_ids = arguments.rule_ids
    debug_req = arguments.debug
    requests_db = arguments.req_db if arguments.req_db is not None else 'requests.txt'
    if arguments.test_type == 'watchdog':
        #when doing a watch dog test use the index as the doc_id
        doc_id = index_name
        watchdog_run(blacklab_host, arguments.ann_host, doc_id, arguments.concurrency, arguments.redis_container,
                     arguments.tomcat_log_file, repeat)
    elif arguments.test_type == 'watchdog-analysis':
        watch_dog_log_analysis(arguments.fl_index)
    else:
        all_patterns_db: Dict[str, str] = _read_input_file(requests_db, True)
        if "," in blacklab_host:
            hosts = blacklab_host.split(",")
            search_perf_test_multiple(
                hosts[0],
                hosts[1],
                index_name,
                all_patterns_db,
                rule_ids,
                arguments.save_bl_res,
                repeat=repeat,
                debug=debug_req)
        else:
            #Patt -> Id
            search_perf_test(
                blacklab_host,
                index_name,
                all_patterns_db,
                rule_ids,
                arguments.save_bl_res,
                repeat=repeat,
                debug=debug_req)
