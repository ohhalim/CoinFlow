import http from 'k6/http';
import ws from 'k6/ws';
import { check, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = __ENV.WS_URL || BASE_URL.replace(/^http/, 'ws');
const WS_PATH = __ENV.WS_PATH || '/ws';
const WS_PROTOCOL = __ENV.WS_PROTOCOL || '';
const MARKET = __ENV.MARKET || 'BTC-KRW';
const DURATION = __ENV.DURATION || '30s';
const WS_WARMUP = __ENV.WS_WARMUP || '5s';
const WS_DURATION = __ENV.WS_DURATION || `${durationSeconds(DURATION) + durationSeconds(WS_WARMUP) + 5}s`;
const ORDER_RATE = Number(__ENV.ORDER_RATE || 10);
const ORDER_VUS = Number(__ENV.ORDER_VUS || 8);
const ORDER_MAX_VUS = Number(__ENV.ORDER_MAX_VUS || 20);
const WS_SUBSCRIBERS = Number(__ENV.WS_SUBSCRIBERS || 5);
const BUYER_COUNT = Number(__ENV.BUYER_COUNT || 8);
const SELLER_COUNT = Number(__ENV.SELLER_COUNT || 8);
const PASSWORD = 'password1234';

const createdOrders = new Counter('created_orders');
const createdTrades = new Counter('created_trades');
const orderFailures = new Counter('order_failures');
const serverErrors = new Counter('server_errors');
const wsConnections = new Counter('ws_connections');
const wsConnectionErrors = new Counter('ws_connection_errors');
const stompErrors = new Counter('stomp_errors');
const stompSubscriptions = new Counter('stomp_subscriptions');
const tradeMessages = new Counter('ws_trade_messages');
const currentTradeMessages = new Counter('ws_current_trade_messages');
const orderbookMessages = new Counter('ws_orderbook_messages');
const tradeDeliveryLag = new Trend('ws_trade_delivery_lag', true);
const orderCreateDuration = new Trend('order_create_duration', true);

export const options = {
  scenarios: {
    websocket_subscribers: {
      executor: 'constant-vus',
      vus: WS_SUBSCRIBERS,
      duration: WS_DURATION,
      exec: 'websocketSubscriber',
    },
    order_creation: {
      executor: 'constant-arrival-rate',
      rate: ORDER_RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: WS_WARMUP,
      preAllocatedVUs: ORDER_VUS,
      maxVUs: ORDER_MAX_VUS,
      exec: 'orderCreation',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    order_create_duration: ['p(95)<1000'],
    order_failures: ['count==0'],
    server_errors: ['count==0'],
    ws_connection_errors: ['count==0'],
    stomp_errors: ['count==0'],
    ws_connections: ['count>0'],
    ws_trade_messages: ['count>0'],
    ws_current_trade_messages: ['count>0'],
    ws_orderbook_messages: ['count>0'],
    ws_trade_delivery_lag: ['p(95)<3000'],
  },
};

export function setup() {
  const runId = __ENV.RUN_ID || `${Date.now()}`;
  const runStartedAt = new Date().toISOString();

  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { endpoint: 'health', type: 'setup' } });
  failOnServerError(health);
  check(health, {
    'application is healthy': (response) => response.status === 200,
  });

  const buyers = [];
  const sellers = [];

  for (let index = 0; index < BUYER_COUNT; index += 1) {
    const user = signupLogin(`k6-ws-buyer-${runId}-${index}@example.com`, `k6-ws-buyer-${index}`);
    deposit(user.token, 'KRW', '100000000');
    buyers.push(user);
  }

  for (let index = 0; index < SELLER_COUNT; index += 1) {
    const user = signupLogin(`k6-ws-seller-${runId}-${index}@example.com`, `k6-ws-seller-${index}`);
    deposit(user.token, 'BTC', '10');
    sellers.push(user);
  }

  return { runId, runStartedAt, buyers, sellers };
}

export function websocketSubscriber(data) {
  const user = data.buyers[(exec.vu.idInTest - 1) % data.buyers.length];
  const runStartedAtMillis = Date.parse(data.runStartedAt);
  const headers = {
    Authorization: `Bearer ${user.token}`,
  };
  if (WS_PROTOCOL) {
    headers['Sec-WebSocket-Protocol'] = WS_PROTOCOL;
  }

  const response = ws.connect(`${WS_URL}${WS_PATH}`, {
    headers,
    tags: { endpoint: 'ws:stomp', market: MARKET },
  }, (socket) => {
    const state = { subscribed: false, runStartedAtMillis };

    socket.on('open', () => {
      wsConnections.add(1);
      socket.send(stompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '10000,10000',
        host: 'localhost',
      }));
    });

    socket.on('message', (rawMessage) => {
      const frames = splitFrames(rawMessage);
      for (const frame of frames) {
        handleFrame(socket, state, frame);
      }
    });

    socket.on('error', () => {
      wsConnectionErrors.add(1);
    });

    socket.setInterval(() => {
      socket.send('\n');
    }, 10000);

    socket.setTimeout(() => {
      socket.close();
    }, Math.max(1000, durationSeconds(WS_DURATION) * 1000 + 1000));
  });

  check(response, {
    'websocket handshake returns 101': (res) => res && res.status === 101,
  });

  if (!response || response.status !== 101) {
    if (__ENV.DEBUG_WS === 'true') {
      console.warn(`WebSocket handshake failed: status=${response && response.status}`);
    }
    wsConnectionErrors.add(1);
    sleep(1);
  }
}

export function orderCreation(data) {
  group('WS-LOAD order creation', () => {
    const iteration = exec.scenario.iterationInTest;
    const isBuy = iteration % 2 === 0;
    const users = isBuy ? data.buyers : data.sellers;
    const user = users[iteration % users.length];
    const side = isBuy ? 'BUY' : 'SELL';

    const response = postJson(
      '/api/v1/orders',
      {
        market: MARKET,
        side,
        type: 'LIMIT',
        timeInForce: 'GTC',
        price: '100000000',
        quantity: '0.0001',
        clientOrderId: `k6-ws-${data.runId}-${side}-${iteration}`,
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

function handleFrame(socket, state, rawFrame) {
  const frame = parseFrame(rawFrame);
  if (!frame) {
    return;
  }

  if (frame.command === 'CONNECTED' && !state.subscribed) {
    state.subscribed = true;
    socket.send(stompFrame('SUBSCRIBE', {
      id: `trades-${exec.vu.idInTest}`,
      destination: `/topic/trades/${MARKET}`,
      ack: 'auto',
    }));
    socket.send(stompFrame('SUBSCRIBE', {
      id: `orderbook-${exec.vu.idInTest}`,
      destination: `/topic/orderbook/${MARKET}`,
      ack: 'auto',
    }));
    stompSubscriptions.add(2);
    return;
  }

  if (frame.command === 'ERROR') {
    stompErrors.add(1);
    return;
  }

  if (frame.command !== 'MESSAGE') {
    return;
  }

  if (frame.headers.destination === `/topic/trades/${MARKET}`) {
    recordTradeMessage(frame.body, state.runStartedAtMillis);
  } else if (frame.headers.destination === `/topic/orderbook/${MARKET}`) {
    recordOrderBookMessage(frame.body);
  }
}

function recordTradeMessage(body, runStartedAtMillis) {
  const message = parseJson(body);
  if (!message) {
    stompErrors.add(1);
    return;
  }

  tradeMessages.add(1);
  check(message, {
    'trade feed market matches': (value) => value.market === MARKET,
    'trade feed has eventId': (value) => Number.isFinite(Number(value.eventId)),
    'trade feed has price': (value) => Boolean(value.price),
    'trade feed has quantity': (value) => Boolean(value.quantity),
  });

  const occurredAt = Date.parse(message.tradedAt);
  if (!Number.isNaN(occurredAt) && occurredAt >= runStartedAtMillis) {
    currentTradeMessages.add(1);
    tradeDeliveryLag.add(Math.max(0, Date.now() - occurredAt));
  }
}

function recordOrderBookMessage(body) {
  const message = parseJson(body);
  if (!message) {
    stompErrors.add(1);
    return;
  }

  orderbookMessages.add(1);
  check(message, {
    'orderbook market matches': (value) => value.market === MARKET,
    'orderbook has eventId': (value) => Number.isFinite(Number(value.eventId)),
    'orderbook has bids array': (value) => Array.isArray(value.bids),
    'orderbook has asks array': (value) => Array.isArray(value.asks),
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

function stompFrame(command, headers = {}, body = '') {
  const headerLines = Object.entries(headers)
    .map(([key, value]) => `${key}:${value}`)
    .join('\n');
  return `${command}\n${headerLines}\n\n${body}\u0000`;
}

function splitFrames(rawMessage) {
  return String(rawMessage)
    .split('\u0000')
    .map((frame) => frame.trim())
    .filter((frame) => frame.length > 0);
}

function parseFrame(rawFrame) {
  const normalized = String(rawFrame).replace(/\r\n/g, '\n');
  const separator = normalized.indexOf('\n\n');
  const headerPart = separator >= 0 ? normalized.slice(0, separator) : normalized;
  const body = separator >= 0 ? normalized.slice(separator + 2) : '';
  const lines = headerPart.split('\n').filter((line) => line.length > 0);
  const command = lines.shift();

  if (!command) {
    return undefined;
  }

  const headers = {};
  for (const line of lines) {
    const separatorIndex = line.indexOf(':');
    if (separatorIndex > 0) {
      headers[line.slice(0, separatorIndex)] = line.slice(separatorIndex + 1);
    }
  }

  return { command, headers, body };
}

function parseJson(value) {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
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

function durationSeconds(value) {
  const text = String(value).trim();
  const match = text.match(/^(\d+)(ms|s|m)$/);
  if (!match) {
    return 30;
  }

  const amount = Number(match[1]);
  const unit = match[2];
  if (unit === 'ms') {
    return Math.ceil(amount / 1000);
  }
  if (unit === 'm') {
    return amount * 60;
  }
  return amount;
}
