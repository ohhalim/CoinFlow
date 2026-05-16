import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const ORDER_RATE = Number(__ENV.ORDER_RATE || 10);
const QUERY_RATE = Number(__ENV.QUERY_RATE || 20);
const BUYER_COUNT = Number(__ENV.BUYER_COUNT || 8);
const SELLER_COUNT = Number(__ENV.SELLER_COUNT || 8);
const PASSWORD = 'password1234';

const createdOrders = new Counter('created_orders');
const createdTrades = new Counter('created_trades');
const orderFailures = new Counter('order_failures');
const serverErrors = new Counter('server_errors');
const orderCreateDuration = new Trend('order_create_duration', true);
const marketsDuration = new Trend('markets_duration', true);
const orderbookDuration = new Trend('orderbook_duration', true);
const tradesDuration = new Trend('trades_duration', true);
const walletsDuration = new Trend('wallets_duration', true);
const walletLedgersDuration = new Trend('wallet_ledgers_duration', true);
const fillsDuration = new Trend('fills_duration', true);

export const options = {
  scenarios: {
    order_creation: {
      executor: 'constant-arrival-rate',
      rate: ORDER_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.ORDER_VUS || 8),
      maxVUs: Number(__ENV.ORDER_MAX_VUS || 20),
      exec: 'orderCreation',
    },
    query_mix: {
      executor: 'constant-arrival-rate',
      rate: QUERY_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.QUERY_VUS || 8),
      maxVUs: Number(__ENV.QUERY_MAX_VUS || 20),
      exec: 'queryMix',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    'http_req_duration{type:query}': ['p(95)<500'],
    server_errors: ['count==0'],
  },
};

export function setup() {
  const runId = __ENV.RUN_ID || `${Date.now()}`;

  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { endpoint: 'health', type: 'setup' } });
  failOnServerError(health);
  check(health, {
    'application is healthy': (response) => response.status === 200,
  });

  const buyers = [];
  const sellers = [];

  for (let index = 0; index < BUYER_COUNT; index += 1) {
    const user = signupLogin(`k6-buyer-${runId}-${index}@example.com`, `k6-buyer-${index}`);
    deposit(user.token, 'KRW', '100000000');
    buyers.push(user);
  }

  for (let index = 0; index < SELLER_COUNT; index += 1) {
    const user = signupLogin(`k6-seller-${runId}-${index}@example.com`, `k6-seller-${index}`);
    deposit(user.token, 'BTC', '10');
    sellers.push(user);
  }

  return { runId, buyers, sellers };
}

export function orderCreation(data) {
  group('LOAD-001 order creation', () => {
    const iteration = exec.scenario.iterationInTest;
    const isBuy = iteration % 2 === 0;
    const users = isBuy ? data.buyers : data.sellers;
    const user = users[iteration % users.length];
    const side = isBuy ? 'BUY' : 'SELL';

    const response = postJson(
      '/api/v1/orders',
      {
        market: 'BTC-KRW',
        side,
        type: 'LIMIT',
        timeInForce: 'GTC',
        price: '100000000',
        quantity: '0.0001',
        clientOrderId: `k6-${data.runId}-${side}-${iteration}`,
      },
      authParams(user.token, { endpoint: 'orders:create', type: 'write', side })
    );
    orderCreateDuration.add(response.timings.duration);

    const ok = check(response, {
      'order create returns 201': (res) => res.status === 201,
      'order response has orderId': (res) => Boolean(jsonValue(res, 'orderId')),
    });

    if (ok) {
      createdOrders.add(1);
      createdTrades.add((jsonValue(response, 'trades') || []).length);
    } else {
      orderFailures.add(1);
    }
    failOnServerError(response);
    sleep(0.05);
  });
}

export function queryMix(data) {
  group('LOAD-002 query mix', () => {
    const iteration = exec.scenario.iterationInTest;
    const user = data.buyers[iteration % data.buyers.length];
    const choice = iteration % 6;

    let response;
    if (choice === 0) {
      response = http.get(`${BASE_URL}/api/v1/markets`, {
        tags: { endpoint: 'markets:list', type: 'query' },
      });
      marketsDuration.add(response.timings.duration);
    } else if (choice === 1) {
      response = http.get(`${BASE_URL}/api/v1/markets/BTC-KRW/orderbook?depth=20`, {
        tags: { endpoint: 'orderbook:get', type: 'query' },
      });
      orderbookDuration.add(response.timings.duration);
    } else if (choice === 2) {
      response = http.get(`${BASE_URL}/api/v1/markets/BTC-KRW/trades?limit=20`, {
        tags: { endpoint: 'trades:list', type: 'query' },
      });
      tradesDuration.add(response.timings.duration);
    } else if (choice === 3) {
      response = http.get(`${BASE_URL}/api/v1/wallets`, authParams(user.token, {
        endpoint: 'wallets:list',
        type: 'query',
      }));
      walletsDuration.add(response.timings.duration);
    } else if (choice === 4) {
      response = http.get(`${BASE_URL}/api/v1/wallets/ledgers?limit=50`, authParams(user.token, {
        endpoint: 'wallet_ledgers:list',
        type: 'query',
      }));
      walletLedgersDuration.add(response.timings.duration);
    } else {
      response = http.get(`${BASE_URL}/api/v1/fills?market=BTC-KRW&limit=50`, authParams(user.token, {
        endpoint: 'fills:list',
        type: 'query',
      }));
      fillsDuration.add(response.timings.duration);
    }

    check(response, {
      'query returns 200': (res) => res.status === 200,
    });
    failOnServerError(response);
    sleep(0.02);
  });
}

function signupLogin(email, nickname) {
  const signup = postJson('/api/v1/auth/signup', { email, password: PASSWORD, nickname }, {
    tags: { endpoint: 'auth:signup', type: 'setup' },
  });
  failOnServerError(signup);
  check(signup, {
    'signup created': (response) => response.status === 201,
  });

  const login = postJson('/api/v1/auth/login', { email, password: PASSWORD }, {
    tags: { endpoint: 'auth:login', type: 'setup' },
  });
  failOnServerError(login);
  check(login, {
    'login succeeds': (response) => response.status === 200,
    'login returns access token': (response) => Boolean(jsonValue(response, 'accessToken')),
  });

  return { email, token: jsonValue(login, 'accessToken') };
}

function deposit(token, asset, amount) {
  const response = postJson('/api/v1/wallets/deposit', { asset, amount }, authParams(token, {
    endpoint: 'wallets:deposit',
    type: 'setup',
    asset,
  }));
  failOnServerError(response);
  check(response, {
    [`deposit ${asset} succeeds`]: (res) => res.status === 200,
  });
}

function postJson(path, body, params = {}) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), withJsonHeaders(params));
}

function authParams(token, params = {}) {
  return {
    ...params,
    headers: {
      ...(params.headers || {}),
      Authorization: `Bearer ${token}`,
    },
  };
}

function withJsonHeaders(params = {}) {
  return {
    ...params,
    headers: {
      'Content-Type': 'application/json',
      ...(params.headers || {}),
    },
  };
}

function failOnServerError(response) {
  if (response.status >= 500) {
    serverErrors.add(1);
  }
}

function jsonValue(response, selector) {
  try {
    return response.json(selector);
  } catch {
    return undefined;
  }
}
