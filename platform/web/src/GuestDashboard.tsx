/* Read-only extension of the Hedayah operational dashboard. Public visitors see
 * server-owned aggregates for the general group, never member records or maps.
 * Credentials remain outside this component; page visibility controls polling.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Activity, ArrowUpLeft, Bell, ChevronLeft, Eye, Glasses, Home, Info,
  LayoutDashboard, Loader2, MapPin, Pause, RefreshCw, Settings2,
  ShieldCheck, Smartphone, Users, Watch, Wifi, WifiOff,
} from 'lucide-react';
import { number } from './utils';
import './styles.css';
import './guest-dashboard.css';

const countKeys = ['active', 'offline', 'needsHelp', 'paused', 'sharing', 'eyeConnected', 'watchConnected'] as const;
type CountKey = typeof countKeys[number];
export interface PublicDashboard {
  updatedAt: string;
  group: { name: string; memberCount: number };
  counts: Record<CountKey, number>;
  health: { status: 'ok'; aiConfigured: boolean; aiProvider: 'gemini' | 'openai' | 'local' };
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function parsePublicDashboard(value: unknown): PublicDashboard {
  const invalid = () => { throw new Error('تعذّر قراءة ملخص المجموعة. حاول التحديث مجدداً.'); };
  if (!record(value) || !record(value.group) || !record(value.counts) || !record(value.health)) return invalid();
  const { group, counts, health } = value;
  if (typeof value.updatedAt !== 'string' || value.updatedAt.length > 64 || !Number.isFinite(Date.parse(value.updatedAt)) ||
      typeof group.name !== 'string' || !group.name.trim() || group.name.length > 160 ||
      typeof group.memberCount !== 'number' || !Number.isSafeInteger(group.memberCount) || group.memberCount < 0 ||
      health.status !== 'ok' || typeof health.aiConfigured !== 'boolean' ||
      !['gemini', 'openai', 'local'].includes(String(health.aiProvider))) return invalid();
  for (const key of countKeys) {
    const count = counts[key];
    if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0 || count > group.memberCount) return invalid();
  }
  const total = (counts.active as number) + (counts.offline as number) + (counts.needsHelp as number) + (counts.paused as number);
  if (total !== group.memberCount) return invalid();
  return {
    updatedAt: value.updatedAt,
    group: { name: group.name, memberCount: group.memberCount },
    counts: Object.fromEntries(countKeys.map(key => [key, counts[key]])) as Record<CountKey, number>,
    health: { status: 'ok', aiConfigured: health.aiConfigured, aiProvider: health.aiProvider as PublicDashboard['health']['aiProvider'] },
  };
}

function usePublicDashboard() {
  const [data, setData] = useState<PublicDashboard | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const refreshRequest = useRef<() => void>(() => {});

  useEffect(() => {
    let disposed = false;
    let active: AbortController | null = null;
    let poll: ReturnType<typeof setTimeout> | undefined;
    let refreshQueued = false;
    const visible = () => document.visibilityState !== 'hidden';
    const clearPoll = () => { if (poll !== undefined) clearTimeout(poll); poll = undefined; };

    async function run() {
      clearPoll();
      if (disposed || !visible()) return;
      if (active) { refreshQueued = true; return; }
      const controller = new AbortController();
      active = controller;
      let timedOut = false;
      const deadline = setTimeout(() => { timedOut = true; controller.abort(); }, 12_000);
      setBusy(true);
      try {
        const response = await fetch('/api/v1/public/dashboard', {
          method: 'GET', credentials: 'omit', cache: 'no-store',
          headers: { Accept: 'application/json' }, signal: controller.signal,
        });
        if (!response.ok) throw new Error('تعذّر تحديث الملخص من الخدمة. أعد المحاولة بعد قليل.');
        const snapshot = parsePublicDashboard(await response.json());
        if (!disposed && !controller.signal.aborted && visible()) {
          setData(snapshot);
          setError(null);
        }
      } catch (issue) {
        if (!disposed && visible() && (!controller.signal.aborted || timedOut)) {
          setError(timedOut ? 'استغرق تحديث الملخص وقتاً طويلاً. تحقّق من الاتصال وأعد المحاولة.'
            : issue instanceof Error && issue.message.startsWith('تعذّر') ? issue.message
            : 'تعذّر الاتصال بالخدمة. تحقّق من الشبكة وأعد المحاولة.');
        }
      } finally {
        clearTimeout(deadline);
        if (active === controller) active = null;
        if (!disposed) {
          setBusy(false);
          if (visible()) {
            const delay = refreshQueued ? 0 : 5000;
            refreshQueued = false;
            poll = setTimeout(() => { void run(); }, delay);
          }
        }
      }
    }

    const onVisibility = () => {
      clearPoll();
      if (!visible()) { refreshQueued = false; active?.abort(); }
      else { void run(); }
    };
    refreshRequest.current = () => { void run(); };
    document.addEventListener('visibilitychange', onVisibility);
    void run();
    return () => {
      disposed = true;
      refreshRequest.current = () => {};
      clearPoll();
      active?.abort();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  const refresh = useCallback(() => refreshRequest.current(), []);
  return { data, busy, error, refresh };
}

function Count({ value, name, className = '' }: { value: number | undefined; name: string; className?: string }) {
  return <span className={`gd-count ${className}`} data-testid={name} data-count={value}>
    {value === undefined ? <span className="gd-count-placeholder" aria-label="لم تصل البيانات بعد">—</span> : number(value)}
  </span>;
}

const states = [
  { key: 'active', label: 'نشطون', icon: Wifi, tone: 'active' },
  { key: 'needsHelp', label: 'يحتاجون مساعدة', icon: Bell, tone: 'help' },
  { key: 'paused', label: 'متوقفون مؤقتاً', icon: Pause, tone: 'paused' },
  { key: 'offline', label: 'غير متصلين', icon: WifiOff, tone: 'offline' },
] as const;

export default function GuestDashboard() {
  const { data, busy, error, refresh } = usePublicDashboard();
  const initialLoading = !data && busy && !error;
  const time = data ? new Intl.DateTimeFormat('ar-SA', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(data.updatedAt)) : '';
  const provider = data?.health.aiProvider === 'gemini' ? 'Gemini' : data?.health.aiProvider === 'openai' ? 'OpenAI' : 'مساعد محلي';

  return <div className="app-shell gd-shell" dir="rtl" lang="ar" data-testid="guest-dashboard">
    <a href="#gd-main" className="skip-link">انتقل إلى ملخص المجموعة</a>
    <aside className="sidebar gd-sidebar" aria-label="التنقل في العرض العام">
      <div className="brand brand-light">
        <div className="brand-name"><a href="/" className="gd-brand-home" data-testid="guest-home-link" aria-label="المطوف الذكي — عن المشروع"><strong>المطوف الذكي</strong><small lang="en" dir="ltr">Smart Mutawwif</small></a></div>
        <div className="brand-event"><img src="/brand/hedayathon-logo.png" width="801" height="494" alt="هداية ثون" /><span>هداية ثون • فريق سعي</span></div>
      </div>
      <p className="workspace-label">العرض العام</p>
      <nav>
        <a className="nav-item current" href="#general-overview" aria-current="page"><LayoutDashboard size={20} aria-hidden="true" />ملخص المجموعة</a>
        <a className="nav-item" href="#device-readiness"><Glasses size={20} aria-hidden="true" />حالة الأجهزة</a>
        <a className="nav-item" href="#join-general"><Users size={20} aria-hidden="true" />الانضمام</a>
      </nav>
      <div className="sidebar-bottom gd-sidebar-bottom">
        <div className="privacy-note"><ShieldCheck size={21} aria-hidden="true" /><strong>ملخص متاح للجميع</strong><p>أرقام إجمالية للمجموعة العامة. تفاصيل الأعضاء وإدارة المنصة في مساحة المشرف.</p></div>
        <a className="gd-home-link" href="/"><Home size={17} aria-hidden="true" />عن المشروع <ArrowUpLeft size={16} aria-hidden="true" /></a>
      </div>
    </aside>

    <main className="main-content gd-main" id="gd-main">
      <header className="page-header gd-header" id="general-overview">
        <div>
          <div className="breadcrumb"><Eye size={16} aria-hidden="true" />الداشبورد <ChevronLeft size={14} aria-hidden="true" /><span>العرض العام</span></div>
          <h1>مركز المتابعة</h1>
          <p>ملخص المجموعة العامة وأجهزتها، متاح دون تسجيل دخول.</p>
        </div>
        <div className="header-actions gd-header-actions">
          <div className="gd-action-row">
            <button className="button secondary" onClick={refresh} disabled={busy} data-testid="guest-refresh" aria-label="تحديث ملخص المجموعة">
              <RefreshCw className={busy ? 'spin' : ''} size={17} aria-hidden="true" />تحديث
            </button>
            <a className="button primary" href="/dashboard/admin" data-testid="guest-admin-link"><Settings2 size={17} aria-hidden="true" />إدارة المنصة</a>
          </div>
          <div className={`gd-updated ${error ? 'gd-updated-stale' : ''}`}>
            <span className={`dot ${data && !error ? 'active' : 'offline'}`} aria-hidden="true" />
            {data ? <><span>آخر تحديث</span><time data-testid="guest-updated-at" dateTime={data.updatedAt}>{time}</time></> : <span>بانتظار القراءة الأولى</span>}
          </div>
        </div>
      </header>

      {error && <div className="connection-error gd-error" role="alert" data-testid="guest-error">
        <WifiOff size={20} aria-hidden="true" /><div><strong>{error}</strong><p>{data ? 'الأرقام أدناه من آخر تحديث ناجح؛ قد تكون تغيرت.' : 'لم تصل بيانات المجموعة بعد. لن تظهر أرقام حتى ينجح الاتصال.'}</p></div>
        <button className="button secondary" onClick={refresh} disabled={busy} data-testid="guest-retry">إعادة المحاولة</button>
      </div>}

      <section className="gd-overview" aria-labelledby="gd-group-title" aria-busy={initialLoading}>
        <div className="gd-group-heading">
          <div><Users size={22} aria-hidden="true" /><h2 id="gd-group-title">{data?.group.name ?? 'المجموعة العامة'}</h2></div>
          <p>إجمالي الأعضاء <Count value={data?.group.memberCount} name="guest-member-count" /></p>
        </div>
        {initialLoading && <div className="gd-loading" role="status" data-testid="guest-loading"><Loader2 className="spin" size={16} aria-hidden="true" />جارٍ تحميل الملخص من الخدمة…</div>}
        <dl className="gd-status-strip">
          {states.map(item => <div key={item.key} className={`gd-status-cell gd-state-${item.tone} ${data && data.counts[item.key] > 0 ? 'gd-state-present' : ''}`}>
            <dt><item.icon size={19} aria-hidden="true" /><span>{item.label}</span></dt>
            <dd><Count value={data?.counts[item.key]} name={`guest-count-${item.key}`} /></dd>
          </div>)}
        </dl>
        <p className="gd-state-note"><Info size={15} aria-hidden="true" />غير متصل: لم تصل حالة خلال آخر ٩٠ ثانية، أو لم يتصل العضو بعد التسجيل.</p>
        {data?.group.memberCount === 0 && <div className="gd-empty" data-testid="guest-empty"><Users size={24} aria-hidden="true" /><div><h3>المجموعة بانتظار أول عضو</h3><p>انضم من تطبيق الجوال باسمك. يظهر العدد هنا بعد نجاح الانضمام.</p><a href="#join-general">خطوات الانضمام <ArrowUpLeft size={15} aria-hidden="true" /></a></div></div>}
      </section>

      <div className="gd-content-grid">
        <section className="panel gd-readiness" id="device-readiness" aria-labelledby="gd-readiness-title">
          <div className="gd-panel-heading"><Glasses size={21} aria-hidden="true" /><div><h2 id="gd-readiness-title">الأجهزة والمشاركة</h2><p>حالة النظارة والساعة لدى الأعضاء المتصلين.</p></div></div>
          <dl className="gd-device-list">
            <div><dt><Glasses size={22} aria-hidden="true" /><span><strong>كاميرا Eye متصلة</strong><small>من آخر حالة للأعضاء المتصلين.</small></span></dt><dd><Count value={data?.counts.eyeConnected} name="guest-count-eyeConnected" /></dd></div>
            <div><dt><Watch size={22} aria-hidden="true" /><span><strong>الساعة متصلة</strong><small>اتصال ساعة العضو بالجوال.</small></span></dt><dd><Count value={data?.counts.watchConnected} name="guest-count-watchConnected" /></dd></div>
            <div><dt><MapPin size={22} aria-hidden="true" /><span><strong>مشاركة الموقع مفعّلة</strong><small>يشمل الأعضاء غير المتصلين؛ ليس عدد مواقع حية.</small></span></dt><dd><Count value={data?.counts.sharing} name="guest-count-sharing" /></dd></div>
          </dl>
          <p className="gd-panel-footnote"><ShieldCheck size={16} aria-hidden="true" />هذا العرض لا يعرض أسماء الأعضاء أو مواقعهم أو رسائلهم.</p>
        </section>

        <section className="panel gd-health" aria-labelledby="gd-health-title">
          <div className="gd-panel-heading"><Activity size={21} aria-hidden="true" /><div><h2 id="gd-health-title">حالة الخدمة</h2><p>بحسب آخر استجابة للملخص.</p></div></div>
          <dl className="gd-health-list">
            <div><dt>خدمة المنصة</dt><dd data-testid="guest-health-status">{data ? <span className={`status ${error ? 'stale' : 'active'}`}><span className="dot" aria-hidden="true" />متاحة{error ? ' عند آخر تحديث' : ''}</span> : '—'}</dd></div>
            <div><dt>إعداد المساعد السحابي</dt><dd data-testid="guest-ai-status">{data ? data.health.aiConfigured ? <><strong>مُعدّ</strong><span lang="en" dir="ltr">{provider}</span></> : <><strong>غير مُعدّ</strong><span>المساعد المحلي المحدود</span></> : '—'}</dd></div>
          </dl>
          <p className="gd-health-note">حالة الإعداد لا تعني اختبار ردّ الذكاء الاصطناعي في هذه اللحظة.</p>
          <div className="gd-poll-note"><RefreshCw size={15} aria-hidden="true" /><p>يتحدث الملخص كل ٥ ثوانٍ أثناء فتح الصفحة. يمكن التحديث يدوياً في أي وقت.</p></div>
        </section>
      </div>

      <section className="gd-join" id="join-general" aria-labelledby="gd-join-title">
        <div className="gd-join-heading"><Smartphone size={24} aria-hidden="true" /><div><h2 id="gd-join-title">انضم إلى المجموعة العامة</h2><p>من تطبيق المطوف الذكي على الجوال.</p></div></div>
        <ol className="gd-join-steps"><li><span aria-hidden="true">١</span><p>افتح <strong>ربط المساعد</strong> وأدخل اسمك.</p></li><li><span aria-hidden="true">٢</span><p>اختر <strong>الانضمام للمجموعة العامة</strong> دون رمز.</p></li><li><span aria-hidden="true">٣</span><p>فعّل التنبيهات أو مشاركة الموقع واللقطات عندما ترغب.</p></li></ol>
        <p className="gd-join-note">الانضمام لا يفعّل مشاركة الموقع أو إرسال الصور. للمجموعة الخاصة، استخدم رمز الدعوة من المشرف.</p>
      </section>

      <footer className="gd-footer"><span>المطوف الذكي <span aria-hidden="true">·</span> هداية ثون • فريق سعي</span><span>عرض إجمالي للمجموعة العامة فقط</span></footer>
    </main>
  </div>;
}
