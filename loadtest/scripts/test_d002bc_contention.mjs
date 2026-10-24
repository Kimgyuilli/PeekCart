// Local checks only: k6·클러스터 없이 node 로 d002bc-contention.js 를 그대로 불러 run 판정(D-106 ⑤),
// USERS 산식, spread 대조군의 상품 선택(D-106 ⑦)을 확인한다. 계획서 docs/plans/D-078-remeasure-plan.md §7.
//
// 실행:  node --test loadtest/scripts/test_d002bc_contention.mjs
// 변이:  D002BC_SCRIPT=<사본 경로> node --test loadtest/scripts/test_d002bc_contention.mjs
//
// k6 모듈은 아래 최소 대역으로 바꾼다. 스크립트 본문(init 검사, options, default, handleSummary)은 손대지 않고 실행한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SCRIPT = resolve(process.env.D002BC_SCRIPT || join(HERE, 'd002bc-contention.js'));
const SCRIPT_URL = pathToFileURL(SCRIPT).href;
const S3 = join(HERE, '..', 'reports', '2026-10-24', 's3');

const STUBS = {
  'k6': 'export function check() { return true; } export function sleep() {}',
  'k6/http': `export default { post(url, body) {
    globalThis.__posts.push({ url, body: JSON.parse(body) });
    return { status: 201, timings: { duration: 1 } };
  } };`,
  'k6/execution': 'export default globalThis.__exec;',
  'k6/metrics': 'export class Counter { add() {} } export class Trend { add() {} }',
  'k6/data': 'export class SharedArray { constructor(name, make) { return make(); } }',
  'https://jslib.k6.io/papaparse/5.1.1/index.js': `export default { parse(text) {
    const [head, ...rows] = text.split(/\\r?\\n/).filter((line) => line.trim());
    const keys = head.split(',');
    return { data: rows.map((row) => Object.fromEntries(row.split(',').map((v, i) => [keys[i], v]))) };
  } };`,
};

const HOOKS = `
const STUBS = ${JSON.stringify(STUBS)};
const TARGET = ${JSON.stringify(SCRIPT_URL)};
export async function resolve(specifier, context, next) {
  if (Object.hasOwn(STUBS, specifier)) {
    return { url: 'data:text/javascript,' + encodeURIComponent(STUBS[specifier]), shortCircuit: true };
  }
  return next(specifier, context);
}
export async function load(url, context, next) {
  if (url.split('?')[0] === TARGET) {
    const loaded = await next(url, { ...context, format: 'module' });
    return { ...loaded, format: 'module', shortCircuit: true };
  }
  return next(url, context);
}`;
register('data:text/javascript,' + encodeURIComponent(HOOKS));

globalThis.__exec = { scenario: { iterationInTest: 0 } };
let loads = 0;

// 지난 세션 S3 조건(RPS 170 · 60s · USERS 는 이번 값 450)이 기본이다.
async function loadScript(env = {}) {
  globalThis.__ENV = { GW: 'http://gw.test', RPS: '170', DUR: '60s', USERS: '450', PRODUCT_IDS: '1', ...env };
  globalThis.open = (path) => readFileSync(join(HERE, path), 'utf8');
  globalThis.__posts = [];
  return import(`${SCRIPT_URL}?load=${loads++}`);
}

// --summary-export(legacy) 형식을 handleSummary 가 받는 { values } 형식으로 옮긴다.
function exported(run) {
  const metrics = JSON.parse(readFileSync(join(S3, `${run}.json`), 'utf8')).metrics;
  return Object.fromEntries(Object.entries(metrics).map(([name, values]) => [name, { values }]));
}

function dropped(metrics) {
  return metrics.dropped_iterations ? metrics.dropped_iterations.values.count : 0;
}

// k6 가 threshold 를 평가하는 방식 그대로: count 집계에 식 하나를 적용한다.
function thresholdPasses(options, count) {
  const [expression] = options.thresholds.dropped_iterations;
  const match = /^count(<=|<)(\d+)$/.exec(expression);
  assert.ok(match, `해석할 수 없는 threshold: ${expression}`);
  return match[1] === '<=' ? count <= Number(match[2]) : count < Number(match[2]);
}

function verdict(module, metrics) {
  return /부하 판정: (유효|무효)\./.exec(module.handleSummary({ metrics }).stdout)[1];
}

test('2026-10-24 S3 원본: r1·r2 는 무효, r3·spread 는 유효이고 k6 threshold 와 판정이 같다', async () => {
  const module = await loadScript();
  const expected = [
    ['contend-r1', 5763, '무효'],
    ['contend-r2', 1871, '무효'],
    ['contend-r3', 89, '유효'],
    ['spread-r1', 0, '유효'],   // 0건이면 요약에 metric 자체가 없다
  ];
  for (const [run, count, want] of expected) {
    const metrics = exported(run);
    assert.equal(dropped(metrics), count, `${run} 원본 dropped_iterations`);
    assert.equal(verdict(module, metrics), want, run);
    assert.equal(thresholdPasses(module.options, count), want === '유효', `${run} threshold`);
  }
  assert.equal(exported('spread-r1').dropped_iterations, undefined);
});

test('한도는 목표 반복 수의 1% 이고 경계값은 유효다', async () => {
  const module = await loadScript();
  assert.deepEqual(module.options.thresholds.dropped_iterations, ['count<=102']);
  assert.deepEqual(module.options.thresholds.peekcart_429, ['count==0']);
  for (const [count, want] of [[102, '유효'], [103, '무효']]) {
    const metrics = { dropped_iterations: { values: { count } } };
    assert.equal(verdict(module, metrics), want, String(count));
    assert.equal(thresholdPasses(module.options, count), want === '유효', `threshold ${count}`);
  }
  assert.deepEqual((await loadScript({ DUR: '3m' })).options.thresholds.dropped_iterations, ['count<=306']);
  assert.deepEqual((await loadScript({ DUR: '1m30s' })).options.thresholds.dropped_iterations, ['count<=153']);
});

test('해석할 수 없는 DUR 은 init 에서 죽는다', async () => {
  for (const dur of ['90', '1.5m', '500ms', '0s', '1m30']) {
    await assert.rejects(loadScript({ DUR: dur }), /DUR 을 해석할 수 없다/, dur);
  }
});

test('USERS 450 은 r3 반복 p95 에 산식을 적용한 값이고 요약이 그 값을 찍는다', async () => {
  const module = await loadScript();
  const p95 = (run) => exported(run).iteration_duration.values['p(95)'] / 1000;
  assert.equal(module.usersFor(170, p95('contend-r3')), 450);
  // 버리는 워밍업 run(r1)이나 그 다음 run(r2)으로 잡았다면 나왔을 값. 계획서 §2 의 대안 비교 근거다.
  assert.equal(module.usersFor(170, p95('contend-r1')), 1450);
  assert.equal(module.usersFor(170, p95('contend-r2')), 750);
  assert.equal(module.usersFor(170, p95('spread-r1')), 100);
  assert.match(module.handleSummary({ metrics: exported('contend-r3') }).stdout, /산식 USERS 450 \(이번 USERS 450\)/);
});

test('setup 제한은 USERS 에 비례하고 60초 아래로 내려가지 않는다', async () => {
  assert.equal((await loadScript()).options.setupTimeout, '450s');
  assert.equal((await loadScript({ USERS: '10' })).options.setupTimeout, '60s');
});

test('users.csv·사용자 시드·USERS 가 450 으로 같고, USERS 가 csv 보다 많으면 init 에서 죽는다', async () => {
  const csvRows = readFileSync(join(HERE, 'users.csv'), 'utf8').split(/\r?\n/).filter((l) => l.trim()).length - 1;
  const seed = readFileSync(join(HERE, '..', 'sql', 'd002bc-user-seed.sql'), 'utf8');
  const seeded = Number(/FROM seq WHERE n < (\d+)\)/.exec(seed)[1]);
  assert.equal(csvRows, 450);
  assert.equal(seeded, 450);
  await assert.rejects(loadScript({ USERS: '451' }), /users\.csv 에 450명뿐인데 USERS=451/);
});

test('spread 는 전역 순번으로 상품 1,000개를 고르게 돌고 contend 는 첫 상품만 쓴다', async () => {
  const ids = Array.from({ length: 1000 }, (_, i) => 1001 + i);
  const data = { tokens: Array.from({ length: 450 }, (_, i) => `token-${i}`) };
  const run = async (mode) => {
    const module = await loadScript({ MODE: mode, PRODUCT_IDS: ids.join(',') });
    // constant-arrival-rate 처럼 반복을 VU 450개에 돌려 준다. __ITER 는 VU 별 번호, iterationInTest 는 전역 번호다.
    for (let i = 0; i < 10200; i++) {
      globalThis.__VU = (i % 450) + 1;
      globalThis.__ITER = Math.floor(i / 450);
      globalThis.__exec.scenario.iterationInTest = i;
      module.default(data);
    }
    return globalThis.__posts.filter((p) => p.url.endsWith('/api/v1/cart/items')).map((p) => p.body.productId);
  };

  const spread = await run('spread');
  const counts = new Map();
  for (const id of spread) counts.set(id, (counts.get(id) || 0) + 1);
  assert.equal(counts.size, 1000, '쓰인 상품 수');
  assert.ok([...counts.values()].every((n) => n === 10 || n === 11), '상품당 주문 수');
  assert.ok(spread.every((id, i) => i === 0 || id !== spread[i - 1]), '이웃한 반복이 같은 상품');

  const contend = await run('contend');
  assert.ok(contend.length === 10200 && contend.every((id) => id === ids[0]));
});

test('PRODUCT_IDS 에 빈 값이나 숫자가 아닌 값이 섞이면 init 에서 죽는다', async () => {
  for (const value of ['1,,3', '1,2,x', '0,1']) {
    await assert.rejects(loadScript({ MODE: 'spread', PRODUCT_IDS: value }), /양의 정수가 아닌 값/, value);
  }
});
