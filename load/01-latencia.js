import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    latencia: {
      executor: 'constant-vus',
      vus: 20,
      duration: '1m',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

const cpfs = ["12345678909", "98765432100", "11122233344"]; // Simulando CPFs variados

export default function () {
  const cpf = cpfs[Math.floor(Math.random() * cpfs.length)];
  const url = 'http://host.docker.internal:8083/consultas';
  const payload = JSON.stringify({ cpf: cpf });
  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(url, payload, params);
  
  check(res, {
    'status is 200': (r) => r.status === 200,
    'confianca is ALTA': (r) => {
        try {
            return JSON.parse(r.body).confianca === 'ALTA';
        } catch(e) {
            return false;
        }
    }
  });
}
