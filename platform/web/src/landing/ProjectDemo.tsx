import { useEffect, useId, useReducer, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { ArrowLeft, ArrowRight, Check, Footprints, Glasses, Languages, MousePointer2,
  Pause, Play, Plus, RotateCcw, ScanLine, Undo2 } from 'lucide-react';
import { demoReducer, demoTranslations, initialDemoState } from './demoState';
import type { DemoLanguage } from './demoState';
import './demo.css';

const steps = [
  { label: 'انظر', title: 'من نظرتك تبدأ الحكاية', icon: ScanLine,
    description: 'كاميرا Eye تنقل المشهد إلى الجوال. في التطبيق الفعلي، يبدأ التحليل بعد موافقتك الصريحة.' },
  { label: 'تعرّف', title: 'المعلومة، بجوار المعلم', icon: MousePointer2,
    description: 'اضغط على اسم المعلم في المشهد لتفتح وصفه. التعرّف في التطبيق تقديري وترافقه مصادر عند توفرها.' },
  { label: 'اسأل بلغتك', title: 'اقرأ المشهد بلغتك', icon: Languages,
    description: 'بدّل اللغة لترجمة اللافتة، ثم جرّب السؤال المعدّ لهذا العرض.' },
  { label: 'احسب أشواطك', title: 'شوطٌ تؤكده أنت', icon: Footprints,
    description: 'عدّ مستقل للطواف والسعي. كل زيادة يدوية، ويمكنك التراجع عن آخر شوط.' },
];

/** A geometric illustration, not a photograph, live camera feed, or generated map. */
function CourtyardScene({ titleId, step }: { titleId: string; step: number }) {
  return <svg className="pd-courtyard" viewBox="0 0 640 320" role="img" aria-labelledby={titleId} preserveAspectRatio="xMidYMid slice">
    <title id={titleId}>مشهد هندسي توضيحي للكعبة وساحتها، وليس بثاً حياً</title>
    <rect width="640" height="320" fill="#e6e6d8" />
    <path d="M0 0H640V125L321 160 0 125Z" fill="#eef0e5" />
    <path d="M0 110L321 146 640 110V320H0Z" fill="#d8daca" />
    <path d="M0 123L321 157 640 123V320H0Z" fill="#f2f0e3" />
    <g fill="none" stroke="#c8cfbd" strokeWidth="1">
      <path d="M0 198L320 155 640 198M0 263L320 173 640 263M0 315L320 193 640 315" />
      {[-240, -60, 100, 250, 390, 540, 700, 880].map(x => <path key={x} d={`M320 145L${x} 320`} />)}
    </g>
    <g className="pd-arcade" fill="#c3ccba">
      <path d="M0 62H640V109H0Z" fill="#bcc7b4" />
      <path d="M0 66H640V72H0Z" fill="#f7f6eb" />
      {Array.from({ length: 14 }, (_, index) => <g key={index} transform={`translate(${index * 48 - 14} 0)`}>
        <path d="M4 112V92Q4 77 22 76Q40 77 40 92V112" fill="#eef0e5" />
        <path d="M9 111V95Q9 84 22 82Q35 84 35 95V111" fill="#8da38e" />
        <path d="M0 110V74H6V112M39 112V74H45V110" fill="#dedfcd" />
      </g>)}
      <path d="M0 111H640V118H0Z" fill="#b8c2af" />
    </g>
    <g fill="#abbba5">
      <path d="M92 67V24H99V8H105V24H113V67Z" />
      <path d="M85 27H120V34H85ZM89 49H116V56H89Z" fill="#dce1d0" />
      <path d="M527 67V24H534V8H540V24H548V67Z" />
      <path d="M520 27H555V34H520ZM524 49H551V56H524Z" fill="#dce1d0" />
    </g>
    <g className="pd-scene-architecture">
      <path d="M209 234L322 277 430 230 330 203Z" fill="#d1d4c3" />
      <path d="M228 113L330 135 405 96 301 77Z" fill="#2f3934" />
      <path d="M228 113L330 135V256L228 225Z" fill="#24372f" />
      <path d="M330 135L405 96V211L330 256Z" fill="#142d25" />
      <path d="M233 118L325 138M237 123L325 142M233 211L324 238M236 218L324 244" fill="none" stroke="#435247" strokeWidth="1" />
      <path d="M228 137L330 161V174L228 149Z" fill="#c6a85d" />
      <path d="M330 161L405 124V136L330 174Z" fill="#aa8b44" />
      <path d="M228 140L330 164M228 147L330 171M330 164L405 127M330 171L405 134" fill="none" stroke="#eee0ab" strokeWidth="1" />
      {[243, 261, 279, 297, 315].map((x, index) => <path key={x}
        d={`M${x} ${145 + index * 4.2}l3 -1 3 2 -3 1Z`} fill="#5f552d" />)}
      <path d="M265 168L287 174V215L265 208Z" fill="#ac914d" />
      <path d="M269 173L284 177V209L269 205Z" fill="#d6bd74" />
      <path d="M276 175V207M271 184L282 187M271 196L282 199" stroke="#8f793e" fill="none" />
      <path d="M228 221L330 252 405 207V215L330 261 228 229Z" fill="#c8cdbb" />
      <path d="M236 151V218M318 173V242M341 174V241M395 143V209" stroke="#425449" strokeWidth="1" fill="none" />
    </g>
    <g transform="translate(480 155)">
      <path d="M0 0H80V29H0Z" fill="#416b54" />
      <text x="29" y="19" fill="#f5f3eb" fontFamily="sans-serif" fontSize="12" fontWeight="600">EXIT</text>
      <path d="M12 15H23M18 11L23 15 18 19" fill="none" stroke="#f5f3eb" strokeWidth="1.5" />
      <path d="M9 29V61M70 29V61" stroke="#a9b7a1" strokeWidth="3" />
    </g>
    {step === 3 && <path className="pd-lap-path" d="M221 194C125 228 177 286 313 293C451 300 513 219 411 182"
      fill="none" stroke="#af985d" strokeWidth="2" strokeDasharray="4 7" />}
  </svg>;
}

export default function ProjectDemo() {
  const [state, dispatch] = useReducer(demoReducer, undefined, initialDemoState);
  const [reducedMotion, setReducedMotion] = useState(() => typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const [inView, setInView] = useState(true);
  const root = useRef<HTMLElement>(null);
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const id = useId();
  const step = steps[state.step];
  const translation = demoTranslations[state.language];
  const count = state.counts[state.mode];
  const localDirection = state.language === 'ar' || state.language === 'ur' ? 'rtl' : 'ltr';

  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const changeMotion = () => { setReducedMotion(query.matches); if (query.matches) dispatch({ type: 'pause' }); };
    const changeVisibility = () => { if (document.hidden) dispatch({ type: 'pause' }); };
    query.addEventListener('change', changeMotion);
    document.addEventListener('visibilitychange', changeVisibility);
    const observer = typeof IntersectionObserver !== 'undefined' ? new IntersectionObserver(entries => {
      const visible = entries[0]?.isIntersecting === true && entries[0].intersectionRatio >= .25;
      setInView(visible);
      if (!visible) dispatch({ type: 'pause' });
    }, { threshold: [0, .25] }) : null;
    if (root.current) observer?.observe(root.current);
    return () => {
      query.removeEventListener('change', changeMotion);
      document.removeEventListener('visibilitychange', changeVisibility);
      observer?.disconnect();
    };
  }, []);

  useEffect(() => {
    if (!state.playing || reducedMotion || !inView || document.hidden) return;
    const timeout = window.setTimeout(() => dispatch({ type: 'tick' }), 4500);
    return () => window.clearTimeout(timeout);
  }, [state.playing, state.step, reducedMotion, inView]);

  function tabKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const target = event.key === 'ArrowLeft' ? (index + 1) % 4
      : event.key === 'ArrowRight' ? (index + 3) % 4 : event.key === 'Home' ? 0 : event.key === 'End' ? 3 : null;
    if (target === null) return;
    event.preventDefault();
    dispatch({ type: 'step', step: target });
    tabs.current[target]?.focus();
  }

  return <section className="pd-shell" ref={root} dir="rtl" aria-label="جرّب فكرة المطوف الذكي"
    data-testid="project-demo" data-step={state.step} data-playing={state.playing}>
    <div className="pd-example-line">
      <span><span className="pd-example-square" aria-hidden="true" />عرض توضيحي · بيانات تجريبية</span>
      <button className="pd-reset" data-testid="pd-reset-demo" onClick={() => dispatch({ type: 'reset' })}
        aria-label="إعادة التجربة من البداية" title="إعادة التجربة من البداية"><RotateCcw size={15} /></button>
    </div>

    <div className="pd-steps" role="tablist" aria-label="خطوات التجربة">
      {steps.map((item, index) => <button key={item.label} role="tab" className="pd-step"
        id={`${id}-tab-${index}`} aria-controls={`${id}-panel`} aria-selected={state.step === index}
        tabIndex={state.step === index ? 0 : -1} ref={node => { tabs.current[index] = node; }}
        onKeyDown={event => tabKey(event, index)} onClick={() => dispatch({ type: 'step', step: index })}
        data-testid={`pd-step-${index}`}>
        <item.icon size={18} aria-hidden="true" /><span>{item.label}</span>
      </button>)}
    </div>

    <div id={`${id}-panel`} className="pd-panel" role="tabpanel" aria-labelledby={`${id}-tab-${state.step}`}>
      <div className="pd-device" data-scene-step={state.step}>
        <div className="pd-device-bar"><span><Glasses size={18} aria-hidden="true" />المطوف الذكي</span>
          <span className="pd-device-status">محاكاة عرض النظارة</span></div>
        <div className="pd-view">
          <CourtyardScene titleId={`${id}-scene`} step={state.step} />
          {state.step === 0 && <>
            <div className="pd-focus-window" aria-hidden="true"><div className="pd-scan-line" /></div>
            <div className="pd-view-prompt"><ScanLine size={15} aria-hidden="true" />ثبّت نظرتك للحظة</div>
          </>}
          {state.step === 1 && <>
            <div className="pd-landmark-bounds" aria-hidden="true" />
            <button className={`pd-landmark ${state.landmarkOpen ? 'pd-landmark-open' : ''}`}
              data-testid="pd-landmark" onClick={() => dispatch({ type: 'landmark' })} aria-expanded={state.landmarkOpen}
              aria-controls={`${id}-landmark-info`}><span className="pd-detection-point" aria-hidden="true" />
              الكعبة المشرفة<MousePointer2 size={14} aria-hidden="true" /></button>
            <span className="pd-view-caption">تحديد تقديري للمعلم</span>
          </>}
          {state.step === 2 && <div className="pd-translation" dir={localDirection} lang={state.language}>
            <Languages size={16} aria-hidden="true" /><span><small lang="en" dir="ltr">EXIT</small><strong>{translation.exit}</strong></span>
          </div>}
          {state.step === 3 && <div className="pd-hud-counter">
            <span>{state.mode === 'tawaf' ? 'الطواف' : 'السعي'}</span>
            <output data-testid="pd-count" data-count={count} aria-label={`${count} من 7 أشواط`}>
              <strong>{count}</strong><span>/ 7</span></output>
            <small>{count === 7 ? 'اكتمل العد التجريبي' : 'أشواط مؤكدة يدوياً'}</small>
          </div>}
          <div className="pd-view-bottom"><span aria-hidden="true" /><small>رسم توضيحي</small><span aria-hidden="true" /></div>
        </div>
      </div>

      <div className="pd-explanation">
        <div className="pd-step-heading"><span className="pd-step-number" aria-hidden="true">{state.step + 1}</span>
          <div><h3>{step.title}</h3><p>{step.description}</p></div></div>
        {state.step === 1 && <div className={`pd-info ${state.landmarkOpen ? 'pd-info-open' : ''}`}
          id={`${id}-landmark-info`} data-testid="pd-landmark-info" aria-live="polite">
          {state.landmarkOpen ? <><Check size={16} aria-hidden="true" /><p><strong>الكعبة المشرفة</strong> — مثال لبطاقة معلم؛ تفتح شرحاً عن المشهد الذي اخترته.</p></>
            : <><MousePointer2 size={16} aria-hidden="true" /><p>جرّب الضغط على اسم المعلم داخل إطار النظارة.</p></>}
        </div>}
        {state.step === 2 && <div className="pd-language-demo">
          <div className="pd-language-controls">
            <label className="pd-language-label"><span>لغة التجربة</span><select data-testid="pd-language" value={state.language} onFocus={() => dispatch({ type: 'pause' })}
              onChange={event => dispatch({ type: 'language', language: event.target.value as DemoLanguage })}>
              {(Object.entries(demoTranslations) as [DemoLanguage, typeof translation][]).map(([code, item]) =>
                <option value={code} key={code}>{item.name}</option>)}
            </select></label>
            <button className="pd-question" data-testid="pd-question" lang={state.language} dir={localDirection}
              onClick={() => dispatch({ type: 'question' })}><span>{translation.question}</span><ArrowLeft size={15} aria-hidden="true" /></button>
          </div>
          {state.answerVisible && <div className="pd-answer" data-testid="pd-answer" aria-live="polite">
            <span className="pd-answer-label">إجابة معدّة للعرض</span><p lang={state.language} dir={localDirection}>{translation.answer}</p>
          </div>}
        </div>}
        {state.step === 3 && <div className="pd-counter-demo">
          <div className="pd-counter-modes" aria-label="نوع العد">
            <button data-testid="pd-mode-tawaf" aria-pressed={state.mode === 'tawaf'} onClick={() => dispatch({ type: 'mode', mode: 'tawaf' })}>الطواف</button>
            <button data-testid="pd-mode-sai" aria-pressed={state.mode === 'sai'} onClick={() => dispatch({ type: 'mode', mode: 'sai' })}>السعي</button>
          </div>
          <div className="pd-counter-actions">
            <button className="pd-increment" data-testid="pd-increment" disabled={count >= 7} onClick={() => dispatch({ type: 'increment' })}>
              {count >= 7 ? <Check size={17} /> : <Plus size={17} />}<span>{count >= 7 ? 'سجّلت ٧ أشواط' : 'تأكيد شوط'}</span></button>
            <button className="pd-counter-secondary" data-testid="pd-undo" aria-label="التراجع عن آخر شوط" disabled={!state.history[state.mode].length}
              onClick={() => dispatch({ type: 'undo' })}><Undo2 size={15} aria-hidden="true" /><span>تراجع</span></button>
            <button className="pd-counter-secondary" data-testid="pd-counter-reset" disabled={count === 0}
              onClick={() => dispatch({ type: 'reset-counter' })} aria-label="إعادة عد الأشواط"><RotateCcw size={15} aria-hidden="true" /></button>
          </div>
        </div>}
      </div>
    </div>

    <div className="pd-player">
      <div className="pd-manual-nav"><button data-testid="pd-prev" onClick={() => dispatch({ type: 'step', step: state.step - 1 })}
        disabled={state.step === 0} aria-label="الخطوة السابقة"><ArrowRight size={18} /></button>
        <span aria-hidden="true">{state.step + 1}<span> / 4</span></span>
        <button data-testid="pd-next" onClick={() => dispatch({ type: 'step', step: state.step + 1 })}
          disabled={state.step === 3} aria-label="الخطوة التالية"><ArrowLeft size={18} /></button></div>
      <button className="pd-play" data-testid="pd-play-toggle" disabled={reducedMotion}
        onClick={() => dispatch({ type: state.playing ? 'pause' : 'play' })}
        aria-label={reducedMotion ? 'العرض التلقائي متوقف وفق إعداد تقليل الحركة' : state.playing ? 'إيقاف العرض التلقائي' : 'تشغيل العرض التلقائي'}>
        {state.playing ? <Pause size={15} aria-hidden="true" /> : <Play size={15} aria-hidden="true" />}
        <span>{reducedMotion ? 'تنقّل يدوياً' : state.playing ? 'إيقاف العرض' : 'شغّل الخطوات'}</span>
      </button>
    </div>
    <p className="pd-sr-only" role="status" aria-live="polite" aria-atomic="true">
      الخطوة {state.step + 1} من 4: {step.label}.{state.step === 3 ? ` ${state.mode === 'tawaf' ? 'الطواف' : 'السعي'}: ${count} من 7.` : ''}
    </p>
  </section>;
}
