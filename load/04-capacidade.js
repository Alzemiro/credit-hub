import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    capacidade: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 1000,
      stages: [
        { target: 10, duration: '30s' },
        { target: 50, duration: '30s' },
        { target: 150, duration: '30s' },
        { target: 300, duration: '30s' },
        { target: 500, duration: '30s' },
      ],
    },
  },
};

const cpfs = ["12345678909", "98765432100", "11122233344"]; // Sucesso

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
  });
}
