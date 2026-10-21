import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import ReportPage from './ReportPage';
import { ApiError, getProviders, getReport, getReportFile, getSession } from '../api';

jest.mock('../api', () => ({
  ...jest.requireActual('../api'),
  getProviders: jest.fn(), getSession: jest.fn(), getReport: jest.fn(), getReportFile: jest.fn(),
}));

let element;
let root;

beforeEach(() => {
  global.IS_REACT_ACT_ENVIRONMENT = true;
  jest.useFakeTimers();
  element = document.createElement('div');
  document.body.appendChild(element);
  root = createRoot(element);
  (getProviders).mockResolvedValue({ yandex: true });
  (getSession).mockResolvedValue({
    user: { id: 'alice', username: 'alice', roles: ['user'] },
    yandexLogin: false, profileConsent: 'pending', csrfToken: 'csrf',
  });
});

afterEach(() => {
  act(() => root.unmount());
  jest.runOnlyPendingTimers();
  jest.useRealTimers();
  element.remove();
  jest.restoreAllMocks();
  jest.clearAllMocks();
});

async function render() {
  await act(async () => { root.render(<ReportPage />); });
}

function downloadButton() {
  return Array.from(element.querySelectorAll('button')).find((item) => item.textContent === 'Скачать отчёт');
}

test('authenticated user downloads the prepared report', async () => {
  (getReport).mockResolvedValue({ from: '2026-10-15', to: '2026-10-15', url: '/reports/files/test.json' });
  getReportFile.mockResolvedValue(new Blob(['{"daily":[]}'], { type: 'application/json' }));
  URL.createObjectURL = jest.fn(() => 'blob:report');
  URL.revokeObjectURL = jest.fn();
  const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  await render();
  expect(downloadButton()?.disabled).toBe(false);
  await act(async () => { downloadButton()?.click(); });
  expect(getReport).toHaveBeenCalledWith('', '');
  expect(getReportFile).toHaveBeenCalledWith('/reports/files/test.json');
  expect(URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
  expect(click).toHaveBeenCalledTimes(1);
  expect(element.textContent).toContain('скачан');
});

test('unprocessed period shows the API explanation', async () => {
  (getReport).mockRejectedValue(new ApiError(409, 'Период ещё не обработан Airflow'));
  await render();
  await act(async () => { downloadButton()?.click(); });
  expect(element.querySelector('[role="alert"]')?.textContent).toContain('ещё не обработан');
  expect(downloadButton()?.disabled).toBe(false);
});

test('anonymous session cannot offer a download', async () => {
  (getSession).mockResolvedValue(null);
  await render();
  expect(downloadButton()).toBeUndefined();
  expect(getReport).not.toHaveBeenCalled();
});

test.each([404, 502])('report error %s keeps the Yandex user signed in', async (status) => {
  getSession.mockResolvedValue({
    user: { id: 'yandex-user', username: 'yandex-login', roles: ['offline_access'] },
    yandexLogin: true, profileConsent: 'accepted', csrfToken: 'csrf',
  });
  getReport.mockRejectedValue(new ApiError(status, 'Отчёт недоступен'));
  await render();
  await act(async () => { downloadButton()?.click(); });
  expect(element.querySelector('[role="alert"]')?.textContent).toBe('Отчёт недоступен');
  expect(element.textContent).toContain('yandex-login');
  expect(downloadButton()?.disabled).toBe(false);
});

