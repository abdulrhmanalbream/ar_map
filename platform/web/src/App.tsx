/* THESIS: Group coordination starts with who needs attention and where their consented last location is.
 * OWN-WORLD: Warm ivory work surface, deep teal right rail, restrained Noto Sans Arabic, border-defined operational panels.
 * STORY: Sign in, enroll the first group, identify connection/help state, locate a member, send and verify an alert.
 * FIRST VIEWPORT: Right navigation, compact status strip, member roster beside a dominant readable map; send alert at the top.
 * FORM: Explicitly brief-pinned operational workspace; no fabricated live activity or decorative charts.
 */
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Activity, ArrowLeft, ArrowUpLeft, Bell, Check, CheckCheck, ChevronLeft, CircleHelp, Copy, Eye, Glasses, Layers3, Loader2, LockKeyhole, LogOut, MapPin, MessageSquare, Plus, RefreshCw, Search, Send, Settings2, ShieldCheck, Signal, Users, Watch, Wifi, WifiOff, X } from 'lucide-react';
import { api, ApiError, tokenStore } from './api';
import type { Alert, Device, Filter, Group, Health, Snapshot, User } from './types';
import { ageMs, dateTime, initials, isOffline, LOCATION_STALE_MS, matchesDevice, number, relativeTime, sharedLocation, statusLabel } from './utils';
import LocationMap from './LocationMap';

type Page = 'overview' | 'groups' | 'alerts' | 'settings';
const EMPTY: Snapshot = { devices: [], groups: [], alerts: [], health: null };
const PAGE_TITLES: Record<Page, string> = { overview: 'مركز المتابعة', groups: 'إدارة المجموعات', alerts: 'التنبيهات والرسائل', settings: 'حالة المنصة' };
const errorText = (error: unknown) => error instanceof Error ? error.message : 'تعذّر إتمام العملية. أعد المحاولة.';

function Brand({ compact = false }: { compact?: boolean }) {
  return <div className={`brand ${compact ? 'brand-light' : ''}`}><span className="brand-symbol" aria-hidden="true"><Layers3 size={28} strokeWidth={1.7} /></span><div><strong>سَراب</strong><small>SARAB VISION</small></div></div>;
}

function EmptyState({ icon, title, children, action }: { icon: ReactNode; title: string; children: ReactNode; action?: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon">{icon}</span><h3>{title}</h3><p>{children}</p>{action}</div>;
}

function Login({ onLogin, reason }: { onLogin: (user: User) => void; reason: string }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(reason);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError(''); setBusy(true);
    try {
      const result = await api<{ token: string; user: User }>('/auth/login', { method: 'POST', body: JSON.stringify({ username: username.trim(), password }) }, null);
      tokenStore.set(result.token); setPassword(''); onLogin(result.user);
    } catch (issue) { setError(errorText(issue)); } finally { setBusy(false); }
  };
  return <main className="login-page"><section className="login-story"><Brand compact /><div className="login-message"><span className="login-line" /><h1>رؤية أوضح.<br />مجموعة أقرب.</h1><p>مساحة واحدة لمتابعة أعضاء المجموعة، والاستجابة لطلبات المساعدة، والتواصل في الوقت المناسب.</p><div className="login-features"><span><MapPin size={20} /> المواقع التي يشاركها الأعضاء</span><span><Bell size={20} /> تنبيهات مع تأكيد الاستلام</span><span><ShieldCheck size={20} /> وصول مخصص للمشرفين</span></div></div><small>سَراب · رفيقك في كل خطوة</small></section><section className="login-form-region"><form className="login-form" onSubmit={submit}><span className="section-kicker">لوحة المشرف</span><h2>أهلًا بعودتك</h2><p>سجّل الدخول للوصول إلى مركز المتابعة.</p>{error && <div className="form-error" role="alert">{error}</div>}<label>اسم المستخدم<input autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} required maxLength={100} autoFocus /></label><label>كلمة المرور<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required maxLength={200} /></label><button className="button primary full" disabled={busy}>{busy ? <Loader2 className="spin" size={18} /> : <ArrowLeft size={18} />} {busy ? 'جارٍ تسجيل الدخول…' : 'تسجيل الدخول'}</button><p className="login-security"><LockKeyhole size={16} /> تبقى جلسة الدخول في علامة التبويب الحالية.</p><div className="login-help">تحتاج إلى حساب؟ تواصل مع مسؤول المنصة للحصول على بيانات الدخول.</div></form><small className="login-footer">مركز متابعة سَراب · الإصدار ٣</small></section></main>;
}

function DeviceStatus({ device }: { device: Device }) {
  const type = device.status === 'needs_help' ? 'help' : isOffline(device) ? 'offline' : device.status === 'paused' ? 'stale' : 'active';
  return <span className={`status ${type}`}><i className={`dot ${type}`} />{statusLabel(device)}</span>;
}

function DeviceDetail({ device, onClose, onAlert }: { device: Device; onClose: () => void; onAlert: (device: Device) => void }) {
  const location = sharedLocation(device);
  const stale = location && ageMs(location.recordedAt) > LOCATION_STALE_MS;
  return <section className="device-detail" aria-label={`تفاصيل ${device.name}`}><div className="detail-title"><div className="avatar">{initials(device.name)}</div><div><h3>{device.name}</h3><p>{device.groupName}</p></div><button className="icon-button" onClick={onClose} aria-label="إغلاق تفاصيل العضو"><X size={18} /></button></div><div className="detail-status"><DeviceStatus device={device} /><span>{relativeTime(device.lastSeenAt)}</span></div><dl className="details-grid"><div><dt>مشاركة الموقع</dt><dd>{!device.sharingEnabled ? 'متوقفة باختيار العضو' : !location ? 'بانتظار تحديد الموقع' : stale ? 'آخر موقع قديم' : 'مفعّلة'}</dd></div><div><dt>دقة آخر موقع</dt><dd>{location && Number.isFinite(location.accuracyM) ? `± ${number(Math.round(location.accuracyM))} متر` : 'غير متاحة'}</dd></div><div><dt>تحديث الموقع</dt><dd>{location ? relativeTime(location.recordedAt) : 'لم يُشارك موقع'}</dd></div><div><dt>البطارية</dt><dd>{device.batteryPercent === null ? 'غير متاحة' : `${number(device.batteryPercent)}٪`}</dd></div><div><dt>الوجهة الحالية</dt><dd>{device.destinationName || 'لم تُحدد وجهة'}</dd></div><div><dt>اللغة</dt><dd>{languageLabel(device.language)}</dd></div></dl><div className="hardware-status"><span><Glasses size={17} />كاميرا Eye <b>{device.cameraConnected ? 'متصلة' : 'غير متصلة'}</b></span><span><Activity size={17} />تتبع الحركة <b>{device.imuTracking ? 'يعمل' : 'غير متاح'}</b></span><span><Watch size={17} />الساعة <b>{device.watchConnected ? 'متصلة' : 'غير متصلة'}</b></span></div>{device.lap && <div className="lap-summary"><span>{device.lap.mode === 'tawaf' ? 'الطواف' : device.lap.mode === 'sai' ? 'السعي' : 'الأشواط'}</span><strong>{number(device.lap.count)} <small>من {number(device.lap.target)}</small></strong><span>{device.lap.confidence === 'manual' ? 'عدّ يدوي' : 'تقدير قابل للتصحيح'}</span></div>}<button className="button secondary full" onClick={() => onAlert(device)}><MessageSquare size={17} /> إرسال رسالة للعضو</button></section>;
}
function languageLabel(language: string) { return ({ ar: 'العربية', en: 'الإنجليزية', ur: 'الأردية', id: 'الإندونيسية', tr: 'التركية' } as Record<string, string>)[language.split('-')[0]] || language; }

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(!!tokenStore.get());
  const [loginReason, setLoginReason] = useState('');
  const [page, setPage] = useState<Page>('overview');
  const [data, setData] = useState<Snapshot>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [filter, setFilter] = useState<Filter>('all');
  const [group, setGroup] = useState('');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [alertTarget, setAlertTarget] = useState<{ groupId: string; deviceId?: string } | null>(null);

  const expire = useCallback(() => {
    tokenStore.clear(); setUser(null); setData(EMPTY); setLastUpdated(null); setSelectedId(null);
    setLoginReason('انتهت الجلسة. سجّل الدخول مرة أخرى.');
  }, []);
  useEffect(() => {
    const token = tokenStore.get();
    if (!token) { setChecking(false); return; }
    const controller = new AbortController();
    api<{ user: User }>('/me', { signal: controller.signal }).then(result => setUser(result.user)).catch(issue => {
      if (!controller.signal.aborted) { tokenStore.clear(); setLoginReason(errorText(issue)); }
    }).finally(() => { if (!controller.signal.aborted) setChecking(false); });
    return () => controller.abort();
  }, []);
  useEffect(() => {
    if (!user) return;
    let cancelled = false; let timeout: ReturnType<typeof setTimeout>;
    const controller = new AbortController();
    const poll = async () => {
      try {
        const [devices, groups, alerts, health] = await Promise.all([
          api<{ devices: Device[] }>('/devices', { signal: controller.signal }),
          api<{ groups: Group[] }>('/groups', { signal: controller.signal }),
          api<{ alerts: Alert[] }>('/alerts', { signal: controller.signal }),
          api<Health>('/health', { signal: controller.signal }),
        ]);
        if (!cancelled) { setData({ devices: devices.devices, groups: groups.groups, alerts: alerts.alerts, health }); setError(''); setLastUpdated(new Date().toISOString()); }
      } catch (issue) {
        if (!cancelled) { if (issue instanceof ApiError && issue.status === 401) expire(); else setError(errorText(issue)); }
      } finally { if (!cancelled) { setLoading(false); timeout = setTimeout(poll, 5000); } }
    };
    void poll();
    return () => { cancelled = true; controller.abort(); clearTimeout(timeout); };
  }, [user, refreshKey, expire]);
  const refresh = () => setRefreshKey(value => value + 1);
  const mutationError = (issue: unknown) => { if (issue instanceof ApiError && issue.status === 401) expire(); };
  const logout = async () => {
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* Local credential is cleared even while disconnected. */ }
    tokenStore.clear(); setUser(null); setData(EMPTY); setLoginReason(''); setLastUpdated(null); setSelectedId(null); setPage('overview');
  };
  const sendToDevice = (device: Device) => { setAlertTarget({ groupId: device.groupId, deviceId: device.id }); setPage('alerts'); };
  const selected = data.devices.find(device => device.id === selectedId);
  const visible = data.devices.filter(device => matchesDevice(device, filter, group, search));
  const active = data.devices.filter(device => !isOffline(device) && device.status === 'active').length;
  const offline = data.devices.filter(device => isOffline(device)).length;
  const help = data.devices.filter(device => device.status === 'needs_help').length;
  const pendingHelp = data.alerts.filter(alert => alert.kind === 'help' && !alert.adminAcknowledgedAt).length;

  if (checking) return <main className="session-loading"><Brand /><Loader2 className="spin" /><p>جارٍ التحقق من الجلسة…</p></main>;
  if (!user) return <Login reason={loginReason} onLogin={account => { setUser(account); setLoading(true); setError(''); }} />;
  return <div className="app-shell"><a className="skip-link" href="#content">انتقل إلى المحتوى</a><aside className="sidebar"><Brand compact /><div className="workspace-label">مساحة المشرف</div><nav aria-label="التنقل الرئيسي">{([
    ['overview', MapPin, 'المتابعة المباشرة'], ['groups', Users, 'المجموعات'], ['alerts', Bell, 'التنبيهات'], ['settings', Settings2, 'حالة المنصة'],
  ] as const).map(([id, Icon, label]) => <button key={id} className={`nav-item ${page === id ? 'current' : ''}`} aria-current={page === id ? 'page' : undefined} onClick={() => setPage(id)}><Icon size={20} /><span>{label}</span>{id === 'alerts' && pendingHelp > 0 && <b className="nav-count">{number(pendingHelp)}</b>}</button>)}</nav><div className="sidebar-bottom"><div className="privacy-note"><ShieldCheck size={21} /><strong>الخصوصية أولًا</strong><p>تظهر المواقع فقط بموافقة الأعضاء. لا تُعرض سجلات تنقلهم.</p></div><div className="account"><span className="account-avatar">{initials(user.name)}</span><div><strong>{user.name}</strong><small>مشرف المنصة</small></div><button aria-label="تسجيل الخروج" title="تسجيل الخروج" onClick={() => void logout()}><LogOut size={18} /></button></div></div></aside><main id="content" className="main-content"><header className="page-header"><div><div className="breadcrumb">سَراب <ChevronLeft size={13} /> مساحة المشرف</div><h1>{PAGE_TITLES[page]}</h1><p>{page === 'overview' ? 'كل عضو أقرب. وكل طلب مساعدة في مكانه.' : page === 'groups' ? 'نظّم الأعضاء في مجموعات وشارك رموز الانضمام.' : page === 'alerts' ? 'تواصل بوضوح، وتابع وصول التنبيه وتأكيد استلامه.' : 'تحقق من اتصال الخدمات واستعداد المساعد.'}</p></div><div className="header-actions"><span className={`connection-label ${error ? 'warning' : ''}`}><i className={`dot ${error ? 'stale' : 'active'}`} />{error ? 'التحديث متعذر' : lastUpdated ? 'تحديث كل ٥ ثوانٍ' : 'جارٍ الاتصال'}</span><button className="button primary" onClick={() => { setAlertTarget(null); setPage('alerts'); }}><Send size={17} />إرسال تنبيه</button></div></header>
    {error && <div className="connection-error" role="alert"><WifiOff size={20} /><div><strong>لم تكتمل المزامنة</strong><span>{error} {lastUpdated && `آخر تحديث ناجح: ${relativeTime(lastUpdated)}.`}</span></div><button className="button secondary" onClick={refresh}><RefreshCw size={16} />إعادة المحاولة</button></div>}
    {page === 'overview' && <><div className="overview-status"><button className={filter === 'all' ? 'chosen' : ''} onClick={() => setFilter('all')}><Users size={21} /><div><span>كل الأعضاء</span><strong>{number(data.devices.length)}</strong></div></button><button className={filter === 'active' ? 'chosen' : ''} onClick={() => setFilter('active')}><Wifi size={21} /><div><span>متصلون الآن</span><strong>{number(active)}</strong></div></button><button className={`${filter === 'help' ? 'chosen' : ''} ${help ? 'attention' : ''}`} onClick={() => setFilter('help')}><CircleHelp size={21} /><div><span>يحتاجون المساعدة</span><strong>{number(help)}</strong></div></button><button className={filter === 'offline' ? 'chosen' : ''} onClick={() => setFilter('offline')}><WifiOff size={21} /><div><span>غير متصلين</span><strong>{number(offline)}</strong></div></button></div><div className="overview-tools"><div className="section-title"><h2>أعضاء المجموعة</h2><span>{number(visible.length)} عضو</span></div><div className="filter-controls"><label className="search-field"><Search size={17} /><input aria-label="البحث عن عضو أو مجموعة" value={search} onChange={e => setSearch(e.target.value)} placeholder="ابحث عن عضو أو مجموعة…" /></label><select aria-label="تصفية حسب المجموعة" value={group} onChange={e => setGroup(e.target.value)}><option value="">كل المجموعات</option>{data.groups.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}</select></div></div><div className="operations-grid"><div className="roster-column"><section className="roster-panel"><div className="roster-heading"><span>العضو / حالة الاتصال</span><span>آخر تحديث</span></div>{loading ? <div className="skeleton-list" aria-label="جارٍ تحميل الأعضاء">{[1, 2, 3, 4].map(key => <div className="skeleton-row" key={key} />)}</div> : visible.length ? <div className="roster-list">{visible.map(device => <button className={`member-row ${selectedId === device.id ? 'selected' : ''}`} onClick={() => setSelectedId(device.id)} key={device.id}><span className={`avatar ${device.status === 'needs_help' ? 'help' : ''}`}>{initials(device.name)}</span><span className="member-info"><strong>{device.name}</strong><small>{device.groupName}</small><DeviceStatus device={device} /></span><span className="member-time">{relativeTime(device.lastSeenAt)}<ChevronLeft size={15} /></span></button>)}</div> : <EmptyState icon={<Users size={26} />} title={data.devices.length ? 'لا يوجد أعضاء بهذه التصفية' : 'ابدأ بأول مجموعة'} action={data.devices.length ? <button className="button secondary" onClick={() => { setFilter('all'); setGroup(''); setSearch(''); }}>مسح التصفية</button> : <button className="button primary" onClick={() => setPage('groups')}><Plus size={16} />إنشاء مجموعة</button>}>{data.devices.length ? 'غيّر المجموعة أو كلمات البحث لعرض أعضاء آخرين.' : 'أنشئ مجموعة، ثم أعطِ الأعضاء رمز الانضمام لربط تطبيقاتهم بلوحة المتابعة.'}</EmptyState>}<div className="roster-footer"><ShieldCheck size={14} /> آخر اتصال قبل أكثر من ٩٠ ثانية يُعد غير متصل.</div></section>{selected && <DeviceDetail device={selected} onClose={() => setSelectedId(null)} onAlert={sendToDevice} />}</div><LocationMap devices={visible} selectedId={selectedId} onSelect={setSelectedId} /></div><div className="workspace-footnote"><span><Eye size={15} /> المواقع الأقدم من دقيقة تُعرض بوصفها مواقع قديمة.</span><span>{lastUpdated ? `آخر مزامنة ${relativeTime(lastUpdated)}` : 'بانتظار أول مزامنة'}</span></div></>}
    {page === 'groups' && <GroupsPage groups={data.groups} devices={data.devices} refresh={refresh} onError={mutationError} onView={id => { setGroup(id); setFilter('all'); setPage('overview'); }} />}
    {page === 'alerts' && <AlertsPage groups={data.groups} devices={data.devices} alerts={data.alerts} target={alertTarget} refresh={refresh} onError={mutationError} />}
    {page === 'settings' && <SettingsPage health={data.health} lastUpdated={lastUpdated} error={error} refresh={refresh} />}
  </main></div>;
}

function GroupsPage({ groups, devices, refresh, onError, onView }: { groups: Group[]; devices: Device[]; refresh: () => void; onError: (e: unknown) => void; onView: (id: string) => void }) {
  const [name, setName] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [success, setSuccess] = useState('');
  const create = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError(''); setSuccess('');
    try { await api('/groups', { method: 'POST', body: JSON.stringify({ name: name.trim() }) }); setName(''); setSuccess('تم إنشاء المجموعة. يمكنك الآن إصدار رمز انضمام.'); refresh(); }
    catch (issue) { setError(errorText(issue)); onError(issue); } finally { setBusy(false); }
  };
  return <div className="management-grid"><section className="panel group-list-panel"><div className="panel-heading"><h2>المجموعات</h2><span className="subtle">{number(groups.length)} مجموعة</span></div>{groups.length ? groups.map(item => <GroupRow key={item.id} group={item} online={devices.filter(device => device.groupId === item.id && !isOffline(device)).length} onView={() => onView(item.id)} onError={onError} />) : <EmptyState icon={<Layers3 size={28} />} title="لم تُنشأ مجموعات بعد">ابدأ باسم واضح، مثل اسم الحملة أو الفريق. سيظهر الأعضاء عند انضمامهم من التطبيق.</EmptyState>}</section><aside><form className="panel form-panel" onSubmit={create}><span className="form-title-icon"><Plus size={22} /></span><h2>مجموعة جديدة</h2><p>اجمع أعضاء الفريق في مساحة واحدة لمتابعتهم والتواصل معهم.</p><label>اسم المجموعة<input value={name} onChange={e => setName(e.target.value)} maxLength={100} placeholder="مثال: مجموعة الرحلة الأولى" required /></label>{error && <div className="form-error" role="alert">{error}</div>}{success && <div className="form-success" role="status"><Check size={17} />{success}</div>}<button className="button primary full" disabled={busy || !name.trim()}>{busy ? <Loader2 className="spin" size={17} /> : <Plus size={17} />}{busy ? 'جارٍ الإنشاء…' : 'إنشاء المجموعة'}</button></form><div className="plain-note"><ShieldCheck size={21} /><div><h3>الانضمام بقرار العضو</h3><p>رمز الانضمام يربط التطبيق بالمجموعة. مشاركة الموقع تحتاج تفعيلًا منفصلًا من العضو.</p></div></div></aside></div>;
}

function GroupRow({ group, online, onView, onError }: { group: Group; online: number; onView: () => void; onError: (e: unknown) => void }) {
  const [code, setCode] = useState<{ code: string; expiresAt: string } | null>(null); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [copied, setCopied] = useState(false);
  const generate = async () => { setBusy(true); setError(''); setCopied(false); try { setCode(await api(`/groups/${encodeURIComponent(group.id)}/enrollment`, { method: 'POST' })); } catch (issue) { setError(errorText(issue)); onError(issue); } finally { setBusy(false); } };
  const copy = async () => { try { await navigator.clipboard.writeText(code!.code); setCopied(true); } catch { setError('تعذّر النسخ تلقائيًا. حدّد رمز الانضمام وانسخه يدويًا.'); } };
  return <article className="group-row"><div className="group-row-top"><span className="group-symbol"><Users size={22} /></span><div className="group-name"><h3>{group.name}</h3><p>{number(group.memberCount)} عضو <span>·</span> {number(online)} متصل</p></div><button className="icon-button" aria-label={`عرض أعضاء ${group.name}`} onClick={onView}><ArrowUpLeft size={20} /></button></div><div className="group-row-actions"><button className="button secondary" onClick={() => void generate()} disabled={busy}><Plus size={15} />{busy ? 'جارٍ إصدار الرمز…' : code ? 'إصدار رمز جديد' : 'إصدار رمز انضمام'}</button><button className="text-button" onClick={onView}>عرض الأعضاء<ChevronLeft size={16} /></button></div>{code && <div className="enrollment"><div><span>رمز الانضمام</span><code dir="ltr">{code.code}</code></div><button className="button secondary" onClick={() => void copy()}>{copied ? <Check size={16} /> : <Copy size={16} />}{copied ? 'نُسخ' : 'نسخ الرمز'}</button><small>صالح حتى {dateTime(code.expiresAt)} · حتى ٢٠ انضمامًا.<br />من تطبيق سَراب: المجموعة ← الانضمام برمز.</small></div>}{error && <div className="form-error" role="alert">{error}</div>}</article>;
}

function AlertsPage({ groups, devices, alerts, target, refresh, onError }: { groups: Group[]; devices: Device[]; alerts: Alert[]; target: { groupId: string; deviceId?: string } | null; refresh: () => void; onError: (e: unknown) => void }) {
  const [groupId, setGroupId] = useState(target?.groupId || ''); const [deviceIds, setDeviceIds] = useState<string[]>(target?.deviceId ? [target.deviceId] : []);
  const [audience, setAudience] = useState<'group' | 'members'>(target?.deviceId ? 'members' : 'group'); const [kind, setKind] = useState<'message' | 'regroup'>('message');
  const [message, setMessage] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [success, setSuccess] = useState(''); const [view, setView] = useState<'all' | 'help'>('all');
  useEffect(() => { if (target) { setGroupId(target.groupId); setDeviceIds(target.deviceId ? [target.deviceId] : []); setAudience(target.deviceId ? 'members' : 'group'); } }, [target]);
  const members = devices.filter(device => device.groupId === groupId);
  const visible = view === 'help' ? alerts.filter(alert => alert.kind === 'help') : alerts;
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError(''); setSuccess('');
    try { await api('/alerts', { method: 'POST', body: JSON.stringify({ groupId, ...(audience === 'members' ? { deviceIds } : {}), kind, message: message.trim() }) }); setMessage(''); setSuccess('أُرسل التنبيه. تابع حالة الوصول والتأكيد في سجل التنبيهات.'); refresh(); }
    catch (issue) { setError(errorText(issue)); onError(issue); } finally { setBusy(false); }
  };
  return <div className="alerts-layout"><section className="alert-history"><div className="history-heading"><h2>آخر التنبيهات</h2><div className="segmented"><button className={view === 'all' ? 'active' : ''} onClick={() => setView('all')}>الكل</button><button className={view === 'help' ? 'active' : ''} onClick={() => setView('help')}>طلبات المساعدة</button></div></div>{visible.length ? visible.map(alert => <AlertRow key={alert.id} alert={alert} groupName={groups.find(group => group.id === alert.groupId)?.name || 'مجموعة'} refresh={refresh} onError={onError} />) : <div className="panel"><EmptyState icon={<Bell size={28} />} title={view === 'help' ? 'لا توجد طلبات مساعدة' : 'لا توجد تنبيهات بعد'}>تظهر هنا رسائل المشرف وطلبات الأعضاء، مع حالة وصولها وتأكيد استلامها.</EmptyState></div>}</section><form className="panel form-panel compose-panel" onSubmit={submit}><span className="form-title-icon"><Send size={22} /></span><h2>إرسال تنبيه</h2><p>اختر المجموعة واكتب رسالة قصيرة وواضحة.</p><label>المجموعة<select value={groupId} onChange={event => { setGroupId(event.target.value); setDeviceIds([]); }} required><option value="">اختر المجموعة</option>{groups.map(group => <option key={group.id} value={group.id}>{group.name}</option>)}</select></label><fieldset className="audience-field"><legend>المستلمون</legend><label><input type="radio" name="audience" checked={audience === 'group'} onChange={() => setAudience('group')} />كل المجموعة</label><label><input type="radio" name="audience" checked={audience === 'members'} onChange={() => setAudience('members')} />أعضاء محددون</label></fieldset>{audience === 'members' && <div className="member-checklist">{members.length ? members.map(device => <label key={device.id}><input type="checkbox" checked={deviceIds.includes(device.id)} onChange={e => setDeviceIds(previous => e.target.checked ? [...previous, device.id] : previous.filter(id => id !== device.id))} /><span>{device.name}</span><small>{isOffline(device) ? 'غير متصل' : 'متصل'}</small></label>) : <p>اختر مجموعة تضم أعضاء.</p>}</div>}<label>نوع التنبيه<select value={kind} onChange={e => setKind(e.target.value as 'message' | 'regroup')}><option value="message">رسالة</option><option value="regroup">دعوة للتجمع</option></select></label><label>نص الرسالة<textarea value={message} onChange={e => setMessage(e.target.value)} required maxLength={1000} rows={4} placeholder="اكتب التعليمات التي ستصل إلى الأعضاء…" /><span className="character-count">{number(message.length)} / ١٬٠٠٠</span></label>{error && <div className="form-error" role="alert">{error}</div>}{success && <div className="form-success" role="status"><Check size={17} />{success}</div>}<button className="button primary full" disabled={busy || !groupId || !message.trim() || (audience === 'members' && !deviceIds.length)}>{busy ? <Loader2 className="spin" size={17} /> : <Send size={17} />}{busy ? 'جارٍ الإرسال…' : 'إرسال التنبيه'}</button><p className="small-note">قد يتأخر الوصول للأجهزة غير المتصلة. الاستلام لا يعني أن العضو أكد قراءة التنبيه.</p></form></div>;
}

function AlertRow({ alert, groupName, refresh, onError }: { alert: Alert; groupName: string; refresh: () => void; onError: (e: unknown) => void }) {
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const delivered = alert.recipients.filter(recipient => recipient.deliveredAt).length;
  const acknowledged = alert.recipients.filter(recipient => recipient.acknowledgedAt).length;
  const acknowledge = async () => { setBusy(true); setError(''); try { await api(`/alerts/${encodeURIComponent(alert.id)}/ack`, { method: 'POST' }); refresh(); } catch (issue) { setError(errorText(issue)); onError(issue); } finally { setBusy(false); } };
  return <article className={`alert-row panel ${alert.kind === 'help' ? 'help-alert' : ''}`}><div className="alert-topline"><span className={`alert-type ${alert.kind}`}>{alert.kind === 'help' ? <CircleHelp size={17} /> : alert.kind === 'regroup' ? <Users size={17} /> : <MessageSquare size={17} />}{alert.kind === 'help' ? 'طلب مساعدة' : alert.kind === 'regroup' ? 'دعوة للتجمع' : 'رسالة'}</span><time dateTime={alert.createdAt} title={dateTime(alert.createdAt)}>{relativeTime(alert.createdAt)}</time></div><p className="alert-message">{alert.message}</p><div className="alert-source"><span>{alert.sourceName || 'المشرف'}</span><span>·</span><span>{groupName}</span></div>{alert.kind === 'help' && <div className="help-ack">{alert.adminAcknowledgedAt ? <span className="success-text"><CheckCheck size={16} />اطّلع المشرف على الطلب {alert.adminAcknowledgedBy ? `· ${alert.adminAcknowledgedBy}` : ''}</span> : <button className="button secondary" disabled={busy} onClick={() => void acknowledge()}><Check size={16} />{busy ? 'جارٍ التأكيد…' : 'تأكيد الاطلاع على طلب المساعدة'}</button>}</div>}<details className="delivery-details"><summary><span><Check size={15} />وصل إلى {number(delivered)} / {number(alert.recipients.length)}</span><span><CheckCheck size={16} />أكّد {number(acknowledged)}</span><ChevronLeft size={14} /></summary><div className="recipient-list">{alert.recipients.length ? alert.recipients.map(recipient => <div key={recipient.deviceId}><strong>{recipient.name}</strong><span className={recipient.acknowledgedAt ? 'success-text' : 'subtle'}>{recipient.acknowledgedAt ? 'أكد الاستلام' : recipient.deliveredAt ? 'وصل · بانتظار التأكيد' : 'بانتظار الوصول'}</span></div>) : <p>هذا الطلب ظاهر للمشرف. لا يوجد مستلمون آخرون في المجموعة.</p>}</div></details>{error && <div className="form-error" role="alert">{error}</div>}</article>;
}

function SettingsPage({ health, lastUpdated, error, refresh }: { health: Health | null; lastUpdated: string | null; error: string; refresh: () => void }) {
  return <div className="settings-layout"><section className="panel"><div className="panel-heading"><h2>اتصال الخدمات</h2><button className="button secondary" onClick={refresh}><RefreshCw size={16} />تحديث الحالة</button></div><div className="service-row"><span className="service-symbol"><Signal size={24} /></span><div><h3>خادم المنصة</h3><p>{error ? 'تعذّر الحصول على تحديث حديث.' : lastUpdated ? `آخر تحقق ${relativeTime(lastUpdated)}` : 'بانتظار الاستجابة الأولى'}</p></div><span className={`status ${error || !health ? 'stale' : 'active'}`}><i className={`dot ${error || !health ? 'stale' : 'active'}`} />{error || !health ? 'غير متحقق' : health.status === 'ok' ? 'متاح' : 'يحتاج مراجعة'}</span></div><div className="service-row"><span className="service-symbol"><MessageSquare size={24} /></span><div><h3>المساعد الذكي</h3><p>{health?.aiConfigured ? 'مزوّد الذكاء الاصطناعي مضبوط على الخادم.' : 'تعمل الاستجابات المحلية المحدودة حتى ضبط المزوّد.'}</p></div><span className={`status ${health?.aiConfigured ? 'active' : 'stale'}`}>{health?.aiConfigured ? 'المزوّد مضبوط' : 'الوضع المحلي'}</span></div><div className="service-row"><span className="service-symbol"><LockKeyhole size={24} /></span><div><h3>حماية الجلسة</h3><p>رمز الدخول محفوظ في جلسة علامة التبويب. لا تُعرض مفاتيح المزوّد في لوحة التحكم.</p></div><span className="status active">جلسة مصادق عليها</span></div></section><section className="panel privacy-settings"><ShieldCheck size={30} /><h2>المشاركة تحت تحكم العضو</h2><p>تحتفظ المنصة بآخر موقع يختار العضو مشاركته. عند إيقاف المشاركة، يُحذف الموقع من المنصة والخريطة.</p><ul><li>لا يوجد سجل مسارات أو مواقع سابقة.</li><li>تتطلب الخريطة وقائمة الأعضاء تسجيل الدخول.</li><li>لا تتضمن التنبيهات تسجيلات الكاميرا.</li></ul><div className="version-label">نسخة المنصة <b dir="ltr">{health?.version || '—'}</b></div></section></div>;
}
