export interface Session {
  user: { id: string; username: string; roles: string[] };
  csrfToken: string;
  profileConsent: 'pending' | 'accepted' | 'declined';
  yandexLogin: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

let csrfToken: string | null = null;
let queue: Promise<void> = Promise.resolve();
let sessionRequest: Promise<Session | null> | null = null;

// Cookie rotates after each protected request, so the next request waits for it.
function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const result = queue.then(async () => {
    const headers = new Headers(options.headers);
    headers.set('Accept', 'application/json');
    if (options.method === 'POST' && csrfToken) {
      headers.set('X-CSRF-Token', csrfToken);
    }

    const response = await fetch(path, {
      ...options,
      headers,
      credentials: 'include',
      mode: 'same-origin',
      cache: 'no-store',
    });

    if (!response.ok) {
      if (response.status === 401) csrfToken = null;
      const message = response.status === 401
        ? 'Сессия завершилась. Войдите снова.'
        : response.status === 403
          ? 'Запрос отклонён. Обновите страницу и повторите.'
          : 'Не удалось выполнить запрос. Попробуйте ещё раз.';
      const problem = await response.json().catch(() => null);
      throw new ApiError(response.status, typeof problem?.detail === 'string' ? problem.detail : message);
    }

    if (response.status === 204) return undefined as T;
    const data = await response.json();
    if (typeof data.csrfToken === 'string') csrfToken = data.csrfToken;
    return data as T;
  });

  queue = result.then(() => undefined, () => undefined);
  return result;
}

// React StrictMode can start the effect twice; both calls share one request.
export function getSession(): Promise<Session | null> {
  if (!sessionRequest) {
    sessionRequest = request<Session>('/auth/session')
      .catch((error) => {
        if (error instanceof ApiError && error.status === 401) return null;
        throw error;
      })
      .finally(() => { sessionRequest = null; });
  }
  return sessionRequest;
}

export const getProviders = () => request<{ yandex: boolean }>('/auth/providers');
export const refreshSession = () => request<Session>('/api/me');
export interface Report {
  from: string;
  to: string;
  timezone: string;
  daily: Array<{
    report_date: string;
    display_name: string;
    prosthesis_id: string;
    model: string;
    samples: string | number;
    successful_movements: string | number;
    avg_response_ms: number | null;
    min_battery_pct: number | null;
  }>;
}

export const getReport = (from: string, to: string) => request<Report>(
  `/reports${from || to ? `?${new URLSearchParams({ from, to })}` : ''}`,
);
export const saveConsent = (accepted: boolean) => request<Session>('/auth/consent', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ accepted }),
});

export async function logout(): Promise<void> {
  try {
    await request<void>('/auth/logout', { method: 'POST' });
    csrfToken = null;
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 502)) {
      csrfToken = null;
    }
    throw error;
  }
}
