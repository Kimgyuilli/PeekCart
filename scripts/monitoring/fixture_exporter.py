#!/usr/bin/env python3
"""Synthetic series only; never connects to a PeakCart application or cloud service."""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

STATE = os.environ.get('FIXTURE_STATE', '/fixture/state.json')
INSTANCE = os.environ.get('FIXTURE_INSTANCE', 'fixture-a')
last = time.monotonic()
counters = {}


def metrics():
    global last
    now = time.monotonic()
    elapsed, last = now - last, now
    mode = json.load(open(STATE))['mode']
    breach = mode in ('transient', 'sustained')
    lines = []

    def emit(name, value, **labels):
        labels['instance'] = INSTANCE
        text = ','.join(f'{k}="{v}"' for k, v in sorted(labels.items()))
        lines.append(f'{name}{{{text}}} {value}')

    def counter(app, cache, operation, rate):
        key = (app, cache, operation)
        counters[key] = counters.get(key, 0.0) + elapsed * rate
        emit('cache_fallback_total', counters[key], application=app, cache=cache, operation=operation)

    apps = ('notification-service', 'order-service', 'payment-service', 'product-service')
    for app in apps:
        emit('dlq_backlog', int(breach), application=app)
        emit('outbox_backlog', int(breach), application=app, status='failed')
        emit('outbox_backlog', 99, application=app, status='pending')
    emit('dlq_backlog', 99, application='user-service')
    emit('outbox_backlog', 99, application='user-service', status='failed')
    for status in ('open', 'refund_failed'):
        emit('saga_compensation_backlog', int(breach), application='order-service', status=status)
        emit('saga_compensation_backlog', 99, application='payment-service', status=status)
    emit('saga_compensation_backlog', 99, application='order-service', status='resolved')
    for cache in ('product', 'products', 'productStock'):
        counter('product-service', cache, 'get', 1 if breach else 0)
        counter('product-service', cache, 'evict', 1 if mode == 'sustained' else 0)
        counter('product-service', cache, 'put', 3)
        counter('order-service', cache, 'get', 3)
        counter('order-service', cache, 'evict', 3)
    return '\n'.join(lines) + '\n'


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/metrics':
            self.send_error(404)
            return
        payload = metrics().encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; version=0.0.4')
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    HTTPServer(('0.0.0.0', 8000), Handler).serve_forever()
