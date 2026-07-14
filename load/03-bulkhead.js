import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    bulkhead: {
      executor: 'constant-vus',
      vus: 40,
      duration: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<4000'], // o deadline global de 3s deve segurar
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const cpf = "11111111111"; // Quod lento 8s
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
