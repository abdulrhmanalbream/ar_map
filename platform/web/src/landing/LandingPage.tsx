/*
THESIS: A field guide to a religious companion: look, understand, then ask.
OWN-WORLD: Hedayah forest fields, ivory folios, restrained gold, original event
logo and the established Arabic type. Numbered entries carry an actual sequence.
STORY: Visitors try an illustrated Eye journey, learn what happens on their
devices, then understand the privacy and prototype limits.
FIRST VIEWPORT: A compact forest masthead; a large two-line promise and gold
action on the right; the interactive illustrated camera guide dominates left.
FORM: Field guide, candidate 5 of 6, surface seed 7e3fa866; degraded roll had
no challengers. The demo owns the single authored motion moment.
*/
import { ArrowLeft, ArrowUp, ArrowUpLeft, Plus } from 'lucide-react';
import ProjectDemo from './ProjectDemo';
import './landing.css';

const journey = [
  {
    number: '١',
    context: 'قبل أن تبدأ',
    title: 'انضم، واختر لغتك.',
    description: 'انضم للمجموعة العامة باسمك، أو استخدم رمز دعوة لمجموعة خاصة. اختر لغة المرافق في التطبيق؛ مشاركة اللقطات تبدأ بعد موافقتك، لا بمجرد الانضمام.',
  },
  {
    number: '٢',
    context: 'من كاميرا Eye',
    title: 'انظر، وثبّت المشهد لحظة.',
    description: 'وجّه النظارة إلى معلم أو لوحة. بعد التفعيل، يحاول التطبيق قراءة لقطة كل ١٢ ثانية تقريباً أثناء فتح الشاشة وثبات المشهد. ويمكنك طلب لقطة بنفسك.',
  },
  {
    number: '٣',
    context: 'على شاشة الجوال',
    title: 'اضغط ما تريد فهمه.',
    description: 'اختر عنصراً ظاهراً لتفتح لقطته ومعلومته المرجعية أو ترجمة نصّه. تبقى هذه اللقطة ثابتة أثناء حديثك عنها.',
  },
  {
    number: '٤',
    context: 'بلغتك',
    title: 'اسأل، واستمع إلى المعنى.',
    description: 'اكتب سؤالك أو تحدث، ثم اقرأ جواب Gemini أو استمع إليه إذا كانت خدمة النطق متاحة. تستطيع مراجعة مصادر المعلومات والعودة إلى البث متى شئت.',
  },
];

const landmarks = [
  { name: 'الكعبة المشرفة', detail: 'قبلة المسلمين', url: 'https://alharamain.gov.sa/public/?page=kaaba-ar' },
  { name: 'مقام إبراهيم', detail: 'معلومة عن المعلم', url: 'https://alharamain.gov.sa/public/?page=maqam-ibrahim-ar' },
  { name: 'الحجر الأسود', detail: 'مرجع بداية الطواف', url: 'https://alharamain.gov.sa/public/?page=The_Black_Stone-ar' },
  { name: 'الصفا والمروة والمسعى', detail: 'المعلمان والممر بينهما', url: 'https://saudipedia.com/en/safa-and-marwa' },
];

const questions = [
  {
    question: 'هل التجربة في هذه الصفحة تستخدم الكاميرا أو Gemini؟',
    answer: 'لا. هذه تجربة تفاعلية مرسومة لشرح الفكرة، بإجابات توضيحية معدّة مسبقاً. لا تفتح كاميرتك أو الميكروفون، ولا ترسل صوراً أو أسئلة إلى الذكاء الاصطناعي. الاستخدام الفعلي يتم من تطبيق الجوال مع كاميرا Eye.',
  },
  {
    question: 'متى يرسل التطبيق الصور، وهل يحفظها؟',
    answer: 'بعد موافقتك، يرسل التطبيق لقطات مصغّرة عبر سيرفر المشروع إلى Gemini أثناء فتح شاشة المرافق وثبات المشهد، أو عند طلب لقطة يدوياً. يمكنك إيقاف التلقائي مؤقتاً أو إلغاء المشاركة. لا يرفع بث فيديو مستمراً، ولا يحفظ تطبيقنا أو سيرفرنا الصور في ملفات أو سجلات؛ تبقى مؤقتاً في الذاكرة. قد تطبق شروط معالجة البيانات لدى Google على اللقطات المرسلة. مشاركة الموقع لها موافقة مستقلة.',
  },
  {
    question: 'هل يتعرف على كل معلم ويحدد مكاني بدقة؟',
    answer: 'التعرف تقديري ومحدود حالياً بأربع فئات: الكعبة، مقام إبراهيم، الحجر الأسود، والصفا والمروة والمسعى. تظهر العلامات فوق الصورة في مواضع تقريبية ثنائية الأبعاد، وقد تختفي إذا تغير المشهد. لا تحدد الدور أو الإحداثيات الداخلية، ولا تمثل تتبعاً مكانياً 6DoF. وجود مصدر صحيح للمعلومة لا يؤكد صحة التعرف على ما في الصورة.',
  },
  {
    question: 'هل يحسب أشواط الطواف والسعي تلقائياً؟',
    answer: 'العدّ يدوي من صفر إلى سبعة، مع تراجع وتأكيد قبل التصفير. عدّ الجوال مستقل عن الساعة؛ اختيار الساعة يعرض آخر عدّ وصل منها فقط، وقد يتأخر التحديث. لا يجمع التطبيق العددين، ولا يعتبر ظهور سبعة دليلاً على صحة النسك أو إتمامه.',
  },
  {
    question: 'هل يعمل المرافق دون إنترنت؟',
    answer: 'عدّاد الجوال اليدوي يعمل دون إنترنت ودون ربط مجموعة. التعرف على الصور وسؤال Gemini يحتاجان اتصالاً بالخدمة وربط الجهاز. الصوت يعتمد على اللغات والخدمات المتاحة في هاتفك؛ تبقى الكتابة بديلاً عندما يتعذر التعرف الصوتي أو النطق.',
  },
  {
    question: 'هل يغني عن المرشد، وهل التطبيق متاح في المتاجر؟',
    answer: 'المرافق يعرض معلومات مرجعية محدودة، وليس جهة فتوى. المسائل التي تحتاج حكماً تُراجع مع مرشد مؤهل. الإصدار الحالي نموذج تجريبي بصيغة APK يستهدف One Pro مع XREAL Eye وFold7 وGalaxy Watch8 Classic؛ يحتاج التحقق الميداني على هذه الأجهزة، ولا يوجد رابط متجر معلن حالياً.',
  },
];

export default function LandingPage() {
  return (
    <div className="lp-shell" dir="rtl" lang="ar" id="top">
      <a className="lp-skip" href="#lp-main">انتقل إلى المحتوى</a>
      <div className="lp-opening">
        <header className="lp-header lp-wrap">
          <a className="lp-wordmark" href="#top" aria-label="المطوف الذكي — بداية الصفحة">
            <strong>المطوف الذكي</strong>
            <span>رفيق المعنى في رحلتك</span>
          </a>
          <nav className="lp-nav" aria-label="أقسام الصفحة">
            <a href="#journey">كيف يعمل</a>
            <a href="#features">ما الذي يقدّمه</a>
            <a href="#questions">أسئلة شائعة</a>
          </nav>
          <div className="lp-header-end">
            <a className="lp-admin" href="/dashboard">عرض الداشبورد <ArrowUpLeft size={17} aria-hidden="true" /></a>
            <img className="lp-event-logo" src="/brand/hedayathon-logo.png" width="801" height="494" alt="هداية ثون" />
          </div>
        </header>

        <main id="lp-main">
          <section className="lp-hero lp-wrap" aria-labelledby="lp-hero-title">
            <div className="lp-hero-copy">
              <p className="lp-intro-label">رفيقك في الطواف والسعي</p>
              <h1 id="lp-hero-title"><span>مع كل نظرة،</span><span className="lp-gold">معنى أقرب.</span></h1>
              <p className="lp-hero-description">انظر إلى المعلم، اقرأ قصته، واسأل بلغتك. مرافق ديني يصل كاميرا نظارتك بالمعلومة، ويترك لك مساحة الرحلة.</p>
              <div className="lp-hero-actions">
                <a className="lp-primary" href="#experience">جرّب الرحلة <ArrowLeft size={21} aria-hidden="true" /></a>
                <a className="lp-text-link" href="#journey">تعرّف على الخطوات</a>
              </div>
              <p className="lp-hero-note">تجربة توضيحية هنا. ومرافق بكاميرا Eye في التطبيق.</p>
              <div className="lp-guide-index" aria-hidden="true">
                <span>نظرة</span><i /><span>معلومة</span><i /><span>سؤال</span>
              </div>
            </div>

            <div className="lp-experience" id="experience" role="region" aria-label="تجربة المشروع التوضيحية">
              <ProjectDemo />
            </div>
          </section>

          <div className="lp-opening-foot lp-wrap">
            <p><span className="lp-opening-mark" aria-hidden="true" />النظارة ترى المشهد. الجوال يفتح معناه.</p>
            <p>نموذج تجريبي <span aria-hidden="true">·</span> من فريق سعي</p>
          </div>

          <section className="lp-journey lp-reading-surface" id="journey" aria-labelledby="lp-journey-title">
            <div className="lp-wrap lp-guide-layout">
              <div className="lp-section-intro">
                <p className="lp-section-label">دليل البداية</p>
                <h2 id="lp-journey-title">رحلة واضحة.<br />{' '}خطوة بعد خطوة.</h2>
                <p>من أول ربط للجهاز إلى سؤال عن لقطة اخترتها بنفسك.</p>
                <a className="lp-ink-link" href="#experience">عد إلى التجربة <ArrowLeft size={18} aria-hidden="true" /></a>
              </div>
              <ol className="lp-steps">
                {journey.map(step => (
                  <li className="lp-step" key={step.number}>
                    <span className="lp-step-number" aria-hidden="true">{step.number}</span>
                    <div className="lp-step-content">
                      <span className="lp-step-context">{step.context}</span>
                      <h3>{step.title}</h3>
                      <p>{step.description}</p>
                    </div>
                  </li>
                ))}
              </ol>
            </div>
          </section>

          <section className="lp-features lp-reading-surface" id="features" aria-labelledby="lp-features-title">
            <div className="lp-wrap">
              <div className="lp-features-heading">
                <div>
                  <p className="lp-section-label">المعنى أولاً</p>
                  <h2 id="lp-features-title">تعرف ما تراه.<br />{' '}وتفهمه بلغتك.</h2>
                </div>
                <p>معلومة مرجعية عن معالم محددة، وترجمة للنصوص الواضحة، وسؤال عن الشيء الذي اخترته. تظهر المصادر مع المعلومات لتعود إليها بنفسك.</p>
              </div>

              <div className="lp-reference-spread">
                <div className="lp-reference-heading">
                  <span className="lp-folio-label">في دليل المعالم</span>
                  <h3>أربع فئات.<br />{' '}ومرجع لكل معلومة.</h3>
                  <p>التعرف على الصورة تقديري.<br />المصدر يشرح المعلم، ولا يثبت هويته في اللقطة.</p>
                </div>
                <ul className="lp-landmarks" aria-label="المعالم ومصادرها المرجعية">
                  {landmarks.map(landmark => (
                    <li key={landmark.name}>
                      <a href={landmark.url} target="_blank" rel="noopener noreferrer" aria-label={`${landmark.name} — المصدر المرجعي، يفتح في نافذة جديدة`}>
                        <span><strong>{landmark.name}</strong><small>{landmark.detail}</small></span>
                        <ArrowUpLeft size={21} aria-hidden="true" />
                      </a>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="lp-language-line">
                <p>اختر اللغة الأقرب إليك</p>
                <ul aria-label="اللغات المتاحة">
                  <li lang="ar">العربية</li><li lang="en" dir="ltr">English</li><li lang="ur">اردو</li><li lang="id" dir="ltr">Indonesia</li><li lang="tr" dir="ltr">Türkçe</li>
                </ul>
              </div>
            </div>
          </section>

          <section className="lp-companions lp-reading-surface" aria-labelledby="lp-companions-title">
            <div className="lp-wrap lp-companions-layout">
              <div className="lp-section-intro">
                <p className="lp-section-label">وبقية الرحلة معك</p>
                <h2 id="lp-companions-title">أشواطك.<br />{' '}مجموعتك.<br />{' '}وجهتك.</h2>
                <p>أدوات مساندة تبقى في متناولك، إلى جانب المرافق الديني.</p>
              </div>
              <dl className="lp-companion-list">
                <div>
                  <dt>شوط تسجّله، وعدّ تتذكره.</dt>
                  <dd>عدّاد يدوي للطواف والسعي يعمل على الجوال دون إنترنت، مع تراجع عن الخطأ. ويمكن عرض آخر عدّ وصل من ساعتك، مع توضيح مصدره.</dd>
                  <dd className="lp-entry-note">العدّادان مستقلان؛ لا ينقل التطبيق الأشواط بينهما.</dd>
                </div>
                <div>
                  <dt>تواصل مع مجموعتك.</dt>
                  <dd>طلب مساعدة وتنبيهات للمجموعة عبر الجوال والساعة. يرى المشرف آخر موقع شاركته إذا فعّلت مشاركة الموقع، مع حالته ووقت تحديثه.</dd>
                  <dd className="lp-entry-note">اهتزاز الساعة يعتمد على اتصالها وأذونات الإشعارات.</dd>
                </div>
                <div>
                  <dt>ارجع إلى وجهتك المحفوظة.</dt>
                  <dd>تبقى أدوات الملاحة والوجهات متاحة من الميزات الإضافية في التطبيق، ضمن الخرائط والمسارات الموجودة فيه.</dd>
                  <dd className="lp-entry-note">المسارات تحتاج اعتماداً ميدانياً؛ تمييز الأدوار ما زال مقترحاً.</dd>
                </div>
              </dl>
            </div>
          </section>

          <section className="lp-faq lp-reading-surface" id="questions" aria-labelledby="lp-faq-title">
            <div className="lp-wrap lp-faq-layout">
              <div className="lp-section-intro">
                <p className="lp-section-label">قبل أن تجرّب</p>
                <h2 id="lp-faq-title">صورة أوضح<br />{' '}عن التجربة.</h2>
                <p>ما يفعله المشروع اليوم، وما يحتاج إلى التحقق على الأجهزة.</p>
              </div>
              <div className="lp-questions">
                {questions.map(item => (
                  <details className="lp-question" key={item.question}>
                    <summary><span>{item.question}</span><Plus size={21} aria-hidden="true" /></summary>
                    <p>{item.answer}</p>
                  </details>
                ))}
                <a className="lp-terms-link" href="https://ai.google.dev/gemini-api/terms" target="_blank" rel="noopener noreferrer">شروط معالجة البيانات في Gemini API <ArrowUpLeft size={16} aria-hidden="true" /><span className="lp-sr-only">، تفتح في نافذة جديدة</span></a>
              </div>
            </div>
          </section>
        </main>
      </div>

      <footer className="lp-footer">
        <div className="lp-wrap">
          <div className="lp-footer-top">
            <div>
              <p className="lp-footer-title">تبدأ الحكاية بنظرة.</p>
              <a className="lp-footer-action" href="#experience">جرّب الرحلة بنفسك <ArrowLeft size={24} aria-hidden="true" /></a>
            </div>
            <img src="/brand/hedayathon-logo.png" width="801" height="494" alt="هداية ثون" loading="lazy" />
          </div>
          <div className="lp-footer-bottom">
            <div><strong>المطوف الذكي</strong><span>فريق سعي <span aria-hidden="true">·</span> هداية ثون</span></div>
            <p>مشروع تجريبي لمرافقة الزائر بالمعلومة.</p>
            <div className="lp-footer-links"><a href="/dashboard">الداشبورد</a><a href="#top">إلى البداية <ArrowUp size={16} aria-hidden="true" /></a></div>
          </div>
        </div>
      </footer>
    </div>
  );
}
