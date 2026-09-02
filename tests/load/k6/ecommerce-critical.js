import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const token = __ENV.ACCESS_TOKEN || '';
const errorRate = new Rate('critical_error_rate');
const checkoutLatency = new Trend('checkout_latency', true);

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || '2m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    critical_error_rate: ['rate<0.01'],
  },
  discardResponseBodies: true,
};

function headers() {
  const result = { Accept: 'application/json' };
  if (token) result.Authorization = `Bearer ${token}`;
  return result;
}

export default function () {
  const responses = http.batch([
    ['GET', `${baseUrl}/api/v1/catalog/products?limit=24`, null, { headers: headers(), tags: { route: 'catalog_products' } }],
    ['GET', `${baseUrl}/api/v1/search?q=popular&limit=24`, null, { headers: headers(), tags: { route: 'search' } }],
    ['GET', `${baseUrl}/health/ready`, null, { headers: headers(), tags: { route: 'gateway_ready' } }],
  ]);

  responses.forEach((response) => {
    const ok = check(response, { 'status is not a server error': (r) => r.status < 500 });
    errorRate.add(!ok);
  });

  // Enable only in an isolated test environment with a fresh idempotency key.
  if (__ENV.ENABLE_WRITES === 'true') {
    const checkout = http.post(`${baseUrl}/api/v1/checkout`, '{}', {
      headers: { ...headers(), 'Content-Type': 'application/json', 'Idempotency-Key': `load-${__VU}-${__ITER}` },
      tags: { route: 'checkout' },
    });
    checkoutLatency.add(checkout.timings.duration);
    errorRate.add(!check(checkout, { 'checkout returns a handled response': (r) => [200, 201, 202, 400, 401, 409].includes(r.status) }));
  }
  sleep(Number(__ENV.THINK_SECONDS || 1));
}
