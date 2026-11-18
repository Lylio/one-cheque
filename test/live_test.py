"""Exercise four independent sessions against a real Java HTTP server."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def api(origin, path, fields=None, token=None, expected=200):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    data = urlencode(fields).encode() if fields is not None else None
    request = Request(origin + "/api/tables" + path, data=data, headers=headers)
    try:
        response = urlopen(request, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        payload = json.load(response)
        assert response.status == expected, (response.status, payload)
        return payload


def run(data, scenario):
    settings = dict(os.environ, ONE_CHEQUE_PORT="0", ONE_CHEQUE_DATA=data)
    with subprocess.Popen(["java", "src/OneCheque.java"], cwd=ROOT,
                          env=settings, stdout=subprocess.PIPE, text=True) as server:
        try:
            line = server.stdout.readline()
            assert line.startswith("One Cheque is listening on port "), line
            return scenario("http://127.0.0.1:" + line.split()[-1])
        finally:
            server.terminate()


def check_table(origin):
    for path in ("/", "/app.js", "/style.css"):
        with urlopen(origin + path, timeout=5) as response:
            assert response.status == 200 and response.read()

    host = api(origin, "", {"name": "Alex"})
    table = "/" + host["table"]
    diners = [host] + [api(origin, table + "/join", {"name": name})
                      for name in ("Jamie", "Sam", "Morgan")]
    ids = [str(uuid4()) for _ in diners]

    def add(index):
        return api(origin, table + "/add",
                   {"id": ids[index], "name": 'Meal "special"', "price": "10.00"},
                   diners[index]["token"])

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(add, range(4)))
    assert add(0)["total"] == 4000  # Retrying an add keeps exactly one item.
    for session in diners:
        snapshot = api(origin, table, token=session["token"])
        assert snapshot["total"] == 4000
        assert sum(diner["mine"] for diner in snapshot["diners"]) == 1
        assert all("token" not in diner for diner in snapshot["diners"])
    api(origin, table, expected=401)
    api(origin, table + "/remove", {"id": ids[0]}, diners[1]["token"], expected=404)
    api(origin, table + "/service", {"price": "1"}, diners[1]["token"], expected=403)
    api(origin, table + "/add", {"id": str(uuid4()), "name": "Drink", "price": "1.001"},
        host["token"], expected=422)

    reviewed = api(origin, table + "/service", {"price": "0.03"}, host["token"])
    assert reviewed["total"] == 4003
    assert [diner["charge"] for diner in reviewed["diners"]] == [1, 1, 1, 0]
    corrected = api(origin, table + "/edit", {"id": ids[2], "name": "Salad", "price": "8.50"}, diners[2]["token"])
    assert corrected["total"] == 3853
    api(origin, table + "/close", {"reviewed": reviewed["revision"]}, host["token"], expected=409)
    closed = api(origin, table + "/close", {"reviewed": corrected["revision"]}, host["token"])
    assert closed["closed"] and closed["total"] == 3853
    api(origin, table + "/remove", {"id": ids[0]}, host["token"], expected=409)
    api(origin, table + "/join", {"name": "Late diner"}, expected=409)
    return table, host["token"], closed


def verify_restart(origin, table, token, closed):
    assert api(origin, table, token=token) == closed


with TemporaryDirectory() as saved:
    table, token, closed = run(saved, check_table)
    run(saved, lambda origin: verify_restart(origin, table, token, closed))

print("Passed: four diners, concurrent orders, retry, permissions, rounding, checkout and restart.")
