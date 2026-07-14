import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    breaker: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // não deve falhar a requisição principal (fallback parcial)
  },
};

export default function () {
  const cpf = "00000000000"; // Serasa 500
  const url = 'http://host.docker.internal:8083/consultas';
  const payload = JSON.stringify({ cpf: cpf });
  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(url, payload, params);
  
  check(res, {
    'status is 200': (r) => r.status === 200,
    'confianca is PARCIAL': (r) => {
        try {
            return JSON.parse(r.body).confianca === 'PARCIAL';
        } catch(e) {
            return false;
        }
    }
  });
}
