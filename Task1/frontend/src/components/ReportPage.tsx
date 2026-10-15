import React, { useEffect, useState } from 'react';
import {
  ApiError, getProviders, getSession, logout, refreshSession, saveConsent, Session,
} from '../api';

const button = 'w-full px-4 py-2 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed';

const ReportPage: React.FC = () => {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [yandexEnabled, setYandexEnabled] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState<string | null>(() =>
    new URLSearchParams(window.location.search).has('error')
      ? 'Не удалось войти. Попробуйте ещё раз.' : null);

  useEffect(() => {
    let active = true;
    getSession()
      .then((value) => { if (active) setSession(value); })
      .catch(() => { if (active) setError('Сервис входа недоступен. Попробуйте обновить страницу.'); })
      .finally(() => { if (active) setLoading(false); });
    getProviders()
      .then((value) => { if (active) setYandexEnabled(value.yandex); })
      .catch(() => { /* Local login remains available. */ });
    return () => { active = false; };
  }, []);

  const run = async (action: () => Promise<Session>, success: string) => {
    setBusy(true);
    setError(null);
    setNotice('');
    try {
      setSession(await action());
      setNotice(success);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) setSession(null);
      setError(failure instanceof ApiError ? failure.message : 'Нет связи с сервером. Повторите запрос.');
    } finally {
      setBusy(false);
    }
  };

  const signOut = async () => {
    setBusy(true);
    setError(null);
    setNotice('');
    let signedOut = false;
    try {
      await logout();
      signedOut = true;
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        signedOut = true;
      } else if (failure instanceof ApiError && failure.status === 502) {
        signedOut = true;
        setError('Локальный выход выполнен. Не удалось подтвердить выход из сервиса входа.');
      } else {
        setError('Не удалось выйти. Повторите запрос.');
      }
    } finally {
      if (signedOut) setSession(null);
      setBusy(false);
    }
  };

  return (
    <main className="flex items-center justify-center min-h-screen bg-gray-100 px-4 py-8">
      <section className="w-full max-w-sm p-8 bg-white rounded-lg shadow-md" aria-busy={loading || busy}>
        <h1 className="text-2xl font-bold mb-6">BionicPRO</h1>
        {loading ? <p role="status">Проверяем вход…</p> : !session ? (
          <div className="space-y-3">
            <p className="text-gray-600">Войдите, чтобы посмотреть свои данные.</p>
            <a className={`${button} block text-center bg-blue-500 text-white hover:bg-blue-600`} href="/auth/login">
              Войти
            </a>
            <button
              className={`${button} bg-gray-100 hover:bg-gray-200`}
              disabled={!yandexEnabled}
              onClick={() => { window.location.href = '/auth/login?provider=yandex'; }}
            >
              Войти через Яндекс ID
            </button>
            {!yandexEnabled && <p className="text-sm text-gray-500">Вход через Яндекс ID пока не настроен.</p>}
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <p className="font-medium break-words">{session.user.username}</p>
              <p className="text-sm text-gray-500 break-words">Роли: {session.user.roles.join(', ') || 'не назначены'}</p>
            </div>
            {session.yandexLogin && session.profileConsent === 'pending' && (
              <div className="rounded bg-blue-50 p-4 space-y-3">
                <p className="text-sm">Разрешить BionicPRO получить и сохранить данные вашего профиля из Яндекс ID?</p>
                <button className={`${button} bg-blue-500 text-white hover:bg-blue-600`} disabled={busy}
                  onClick={() => run(() => saveConsent(true), '')}>Разрешить</button>
                <button className={`${button} bg-white hover:bg-gray-100`} disabled={busy}
                  onClick={() => run(() => saveConsent(false), '')}>Отказаться</button>
              </div>
            )}
            {session.yandexLogin && session.profileConsent === 'accepted' && (
              <p className="text-sm text-green-700">Профиль Яндекс ID сохранён с вашего согласия.</p>
            )}
            {session.yandexLogin && session.profileConsent === 'declined' && (
              <p className="text-sm text-gray-600">Согласие не дано. Сервис не загрузил и не сохранил профиль.</p>
            )}
            <div>
              <button className={`${button} bg-blue-500 text-white`} disabled>Скачать отчёт</button>
              <p className="mt-2 text-sm text-gray-500">Сервис отчётов пока не подключён.</p>
            </div>
            <button className={`${button} bg-gray-100 hover:bg-gray-200`} disabled={busy}
              onClick={() => run(refreshSession, 'Сведения обновлены.')}>
              Обновить сведения
            </button>
            <button className={`${button} border border-gray-300 hover:bg-gray-50`} disabled={busy} onClick={signOut}>
              Выйти
            </button>
          </div>
        )}
        {notice && <p className="mt-4 text-sm text-green-700" role="status">{notice}</p>}
        {error && <p className="mt-4 rounded bg-red-100 p-3 text-sm text-red-700" role="alert">{error}</p>}
      </section>
    </main>
  );
};

export default ReportPage;
