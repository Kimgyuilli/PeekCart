#!/usr/bin/env python3
"""Local checks only: never contacts the cluster or a database."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('d078-latency.py')
spec = importlib.util.spec_from_file_location('d078_latency', SCRIPT)
latency = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latency)


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        latency.main(argv)
    return out.getvalue().splitlines()


def tsv(directory, name, header, rows):
    path = Path(directory) / name
    path.write_text('\t'.join(header) + '\n' + ''.join('\t'.join(r) + '\n' for r in rows), encoding='utf-8')
    return str(path)


class PercentileTest(unittest.TestCase):
    def test_nearest_rank_does_not_interpolate(self):
        values = list(range(1, 101))
        self.assertEqual(latency.nearest_rank(values, 50), 50)
        self.assertEqual(latency.nearest_rank(values, 95), 95)
        self.assertEqual(latency.nearest_rank(values, 99), 99)
        self.assertEqual(latency.nearest_rank([7], 99), 7)
        self.assertIsNone(latency.nearest_rank([], 50))

    def test_negative_latency_is_rejected(self):
        with self.assertRaises(ValueError):
            latency.parse_us('-5')


class SagaTest(unittest.TestCase):
    def test_null_rows_are_excluded_not_counted_as_zero(self):
        with tempfile.TemporaryDirectory() as d:
            rows = [[str(i), 'PENDING', str(i * 1000)] for i in range(1, 101)]
            rows += [['101', 'CANCELLED', 'NULL'], ['102', 'CANCELLED', 'NULL'], ['103', 'PENDING', 'NULL']]
            path = tsv(d, 'run-saga.tsv', ['id', 'status', 'reserve_roundtrip_us'], rows)
            lines = run(['saga', path])
        self.assertEqual(lines[0], latency.HEADER)
        self.assertEqual(lines[1], 'saga-roundtrip\t100\t3\t50.0\t95.0\t99.0\t100.0')
        self.assertIn('# excluded status=CANCELLED count=2', lines)
        self.assertIn('# excluded status=PENDING count=1', lines)

    def test_empty_sample_prints_dash_instead_of_inventing_values(self):
        with tempfile.TemporaryDirectory() as d:
            path = tsv(d, 'run-saga.tsv', ['id', 'status', 'reserve_roundtrip_us'], [['1', 'CANCELLED', 'NULL']])
            lines = run(['saga', path])
        self.assertEqual(lines[1], 'saga-roundtrip\t0\t1\t-\t-\t-\t-')


class OutboxTest(unittest.TestCase):
    def test_groups_by_file_and_event_type(self):
        header = ['event_type', 'status', 'publish_wait_us']
        with tempfile.TemporaryDirectory() as d:
            order = tsv(d, 'r1-outbox-order.tsv', header,
                        [['order.created', 'PUBLISHED', '2000'], ['order.created', 'PUBLISHED', '4000'],
                         ['order.created', 'PENDING', 'NULL']])
            product = tsv(d, 'r1-outbox-product.tsv', header,
                          [['stock.reservation.result', 'PUBLISHED', '5000000']])
            lines = run(['outbox', order, product])
        self.assertEqual(lines[1], 'r1-outbox-order:order.created\t2\t1\t2.0\t4.0\t4.0\t4.0')
        self.assertEqual(lines[2], 'r1-outbox-product:stock.reservation.result\t1\t0\t5000.0\t5000.0\t5000.0\t5000.0')


if __name__ == '__main__':
    unittest.main()
