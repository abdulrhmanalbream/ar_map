/** Curated descriptions, checked 2026-09-08. These facts do not prove a visual identification.
 * Keep descriptions modest: no opening hours, crowd claims, live directions or ritual rulings.
 * URLs are maintained here; model output can select IDs but never supply a URL.
 */
export const VISION_LANGUAGES = ['ar', 'en', 'ur', 'id', 'tr'];

export const LANDMARKS = Object.freeze([
  {
    id: 'kaaba', sourceId: 'haramain-kaaba',
    sourceTitle: 'الكعبة المشرفة — الهيئة العامة للعناية بشؤون الحرمين',
    url: 'https://alharamain.gov.sa/public/?page=kaaba-ar',
    labels: { ar: 'الكعبة المشرفة', en: 'The Kaaba', ur: 'خانہ کعبہ', id: 'Kaabah', tr: 'Kâbe' },
    facts: {
      ar: 'تقع الكعبة في المسجد الحرام بمكة، وهي قبلة المسلمين في الصلاة.',
      en: 'The Kaaba is in the Grand Mosque in Makkah. Muslims face it in prayer.',
      ur: 'کعبہ مکہ کی مسجد حرام میں ہے۔ مسلمان نماز میں اس کی سمت رخ کرتے ہیں۔',
      id: 'Kaabah berada di Masjidil Haram, Makkah. Umat Islam menghadap ke arahnya saat salat.',
      tr: 'Kâbe, Mekke’de Mescid-i Haram’dadır. Müslümanlar namazda ona yönelir.',
    },
    visualHint: 'The distinctive large cuboid Kaaba building with black kiswa and gold band. Require that architecture, not a generic black object. A clear photograph or screen may depict the Kaaba; recognizing that depiction never establishes physical presence at the landmark.',
  },
  {
    id: 'maqam_ibrahim', sourceId: 'haramain-maqam',
    sourceTitle: 'مقام إبراهيم — الهيئة العامة للعناية بشؤون الحرمين',
    url: 'https://alharamain.gov.sa/public/?page=maqam-ibrahim-ar',
    labels: { ar: 'مقام إبراهيم', en: 'Maqam Ibrahim', ur: 'مقام ابراہیم', id: 'Maqam Ibrahim', tr: 'Makam-ı İbrahim' },
    facts: {
      ar: 'مقام إبراهيم هو الحجر الذي وقف عليه إبراهيم عليه السلام عند بناء الكعبة مع ابنه إسماعيل.',
      en: 'Maqam Ibrahim is the stone on which Prophet Ibrahim stood while building the Kaaba with his son Ismail.',
      ur: 'مقام ابراہیم وہ پتھر ہے جس پر ابراہیم علیہ السلام اپنے بیٹے اسماعیل کے ساتھ کعبہ بناتے وقت کھڑے ہوئے۔',
      id: 'Maqam Ibrahim adalah batu tempat Nabi Ibrahim berdiri ketika membangun Kaabah bersama putranya, Ismail.',
      tr: 'Makam-ı İbrahim, Hz. İbrahim’in oğlu İsmail ile Kâbe’yi inşa ederken üzerinde durduğu taştır.',
    },
    visualHint: 'The small distinctive golden glazed enclosure near the Kaaba. Require clear architecture and Kaaba context; generic gold lanterns are not this landmark.',
  },
  {
    id: 'black_stone', sourceId: 'haramain-black-stone',
    sourceTitle: 'الحجر الأسود — الهيئة العامة للعناية بشؤون الحرمين',
    url: 'https://alharamain.gov.sa/public/?page=The_Black_Stone-ar',
    labels: { ar: 'الحجر الأسود', en: 'The Black Stone', ur: 'حجر اسود', id: 'Hajar Aswad', tr: 'Hacerülesved' },
    facts: {
      ar: 'الحجر الأسود في أحد أركان الكعبة، وتبدأ أشواط الطواف من محاذاته.',
      en: 'The Black Stone is set in a corner of the Kaaba. Tawaf circuits begin level with it.',
      ur: 'حجر اسود کعبہ کے ایک کونے میں نصب ہے۔ طواف کے چکر اس کے مقابل سے شروع ہوتے ہیں۔',
      id: 'Hajar Aswad terpasang di salah satu sudut Kaabah. Putaran tawaf dimulai sejajar dengannya.',
      tr: 'Hacerülesved Kâbe’nin bir köşesindedir. Tavaf turları onun hizasından başlar.',
    },
    visualHint: 'Dark stone fragments inside the distinctive silver oval surround at the Kaaba corner. Do not infer its location when hidden by people.',
  },
  {
    id: 'safa_marwa', sourceId: 'saudipedia-safa-marwa',
    sourceTitle: 'Safa and Marwa — Saudipedia',
    url: 'https://saudipedia.com/en/safa-and-marwa',
    labels: { ar: 'الصفا والمروة والمسعى', en: 'Safa, Marwa and the Masa’a', ur: 'صفا، مروہ اور مسعیٰ', id: 'Safa, Marwah, dan Masa’a', tr: 'Safa, Merve ve Mes‘â' },
    facts: {
      ar: 'الصفا والمروة جبلان صغيران في الجانب الشرقي من المسجد الحرام، والمسعى هو الممر الذي يصل بينهما.',
      en: 'Safa and Marwa are two small hills on the eastern side of the Grand Mosque. The Masa’a is the passage between them.',
      ur: 'صفا اور مروہ مسجد حرام کے مشرقی حصے میں دو چھوٹی پہاڑیاں ہیں۔ ان کے درمیان راستے کو مسعیٰ کہتے ہیں۔',
      id: 'Safa dan Marwah adalah dua bukit kecil di sisi timur Masjidil Haram. Masa’a adalah jalur di antara keduanya.',
      tr: 'Safa ve Merve, Mescid-i Haram’ın doğusundaki iki küçük tepedir. Aralarındaki geçide Mes‘â denir.',
    },
    visualHint: 'Only identify with a legible Safa/Marwa/Masaa sign plus matching surrounding context. A generic corridor, rocks or green lamps alone are insufficient. Never distinguish Safa versus Marwa or a floor from appearance alone.',
  },
]);

// A small FAQ is for answers only, never additional visual-recognition categories.
export const RITUAL_NOTES = Object.freeze([
  { id: 'tawaf-basics', sourceId: 'haramain-ritual-guide', fact: 'Tawaf consists of seven circuits around the Kaaba, keeping the Kaaba on the left. Each circuit starts and ends aligned with the Black Stone.' },
  { id: 'sai-start-end', sourceId: 'nusuk-umrah-journey', fact: 'Sai consists of seven one-way lengths between Safa and Marwa. It starts at Safa and the seventh length finishes at Marwa.' },
  { id: 'sai-counting', sourceId: 'haramain-ritual-guide', fact: 'For Sai, the outward one-way walk is one count and the return one-way walk is a separate count; a return trip is not a single count.' },
]);

export const COUNTER_CONTEXT = 'Product fact: the current app/watch lap counter is manual, not automatic. A displayed number or image is not proof of ritual completion. Only the person can record/correct their count; the AI must not declare a ritual valid or completed. For uncertainty requiring a ruling, consult a qualified local guide.';

// Exact Arabic scripture/dhikr is fixed content, never generated by the vision model.
// Translations below are concise explanations of meaning maintained by this project.
// Association is contextual information, never evidence of the user's ritual stage or position.
export const DEVOTIONALS = Object.freeze([
  {
    id: 'maqam-quran-2-125', landmarkIds: ['maqam_ibrahim'], sourceIds: ['quran-2-125'],
    arabicText: 'وَاتَّخِذُوا مِن مَّقَامِ إِبْرَاهِيمَ مُصَلًّى',
    titles: { ar: 'تذكير من القرآن عن مقام إبراهيم', en: 'A Quran reminder about Maqam Ibrahim', ur: 'مقام ابراہیم کے بارے میں قرآنی یاد دہانی', id: 'Pengingat Al-Quran tentang Maqam Ibrahim', tr: 'Makam-ı İbrahim hakkında Kur’an hatırlatması' },
    translations: {
      ar: 'المعنى: اتخذوا من مقام إبراهيم مكاناً للصلاة.',
      en: 'Meaning: Take the Station of Ibrahim as a place of prayer.',
      ur: 'مفہوم: مقام ابراہیم کو نماز کی جگہ بناؤ۔',
      id: 'Makna: Jadikanlah Maqam Ibrahim sebagai tempat salat.',
      tr: 'Anlamı: Makam-ı İbrahim’i bir namaz yeri edinin.',
    },
    notes: {
      ar: 'مقتطف من البقرة ١٢٥، وليس صيغة دعاء خاصة بالمقام. التعرف على الصورة لا يحدد موضع الصلاة ولا يؤكد انتهاء الطواف.',
      en: 'Excerpt from Al-Baqarah 2:125, not a special dua for the Maqam. Image recognition does not determine where to pray or confirm that Tawaf is complete.',
      ur: 'البقرہ ۲:۱۲۵ کا اقتباس ہے، مقام کے لیے مخصوص دعا نہیں۔ تصویر کی شناخت نماز کی جگہ یا طواف کی تکمیل کی تصدیق نہیں کرتی۔',
      id: 'Petikan Al-Baqarah 2:125, bukan doa khusus untuk Maqam. Pengenalan gambar tidak menentukan tempat salat atau memastikan tawaf selesai.',
      tr: 'Bakara 2:125’ten bir bölüm; makama özgü bir dua değildir. Görüntü tanıma, namaz yerini belirlemez veya tavafın bittiğini doğrulamaz.',
    },
  },
  {
    id: 'tawaf-dua-2-201', landmarkIds: ['kaaba', 'black_stone'], sourceIds: ['quran-2-201', 'binbaz-tawaf-dhikr'],
    arabicText: 'رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ حَسَنَةً وَقِنَا عَذَابَ النَّارِ',
    titles: { ar: 'دعاء بين الركنين في الطواف', en: 'Dua between the two corners during Tawaf', ur: 'طواف میں دونوں کونوں کے درمیان دعا', id: 'Doa di antara dua sudut saat tawaf', tr: 'Tavafta iki köşe arasında dua' },
    translations: {
      ar: 'المعنى: ربنا ارزقنا الخير في الدنيا والآخرة، واحفظنا من عذاب النار.',
      en: 'Meaning: Our Lord, grant us good in this life and the Hereafter, and protect us from the punishment of the Fire.',
      ur: 'مفہوم: اے ہمارے رب، ہمیں دنیا اور آخرت میں بھلائی عطا فرما اور آگ کے عذاب سے بچا۔',
      id: 'Makna: Ya Tuhan kami, berilah kami kebaikan di dunia dan akhirat, serta lindungilah kami dari azab neraka.',
      tr: 'Anlamı: Rabbimiz, bize dünyada ve ahirette iyilik ver; bizi ateşin azabından koru.',
    },
    notes: {
      ar: 'دعاء من البقرة ٢٠١. يذكر المرجع قوله بين الركن اليماني والحجر الأسود أثناء الطواف. عرض الكعبة أو الحجر لا يثبت أنك بين الركنين؛ ليس لكل شوط دعاء إلزامي خاص.',
      en: 'Dua from Al-Baqarah 2:201. The reference associates it with the stretch from the Yemeni Corner to the Black Stone during Tawaf. Seeing either landmark does not place you there; there is no mandatory special dua for each circuit.',
      ur: 'البقرہ ۲:۲۰۱ کی دعا ہے۔ مرجع میں طواف کے دوران رکن یمانی اور حجر اسود کے درمیان اسے پڑھنے کا ذکر ہے۔ معلم نظر آنے سے آپ کی جگہ ثابت نہیں ہوتی؛ ہر چکر کی الگ لازمی دعا نہیں۔',
      id: 'Doa dari Al-Baqarah 2:201. Rujukan menyebutnya antara Rukun Yamani dan Hajar Aswad saat tawaf. Melihat penanda tidak memastikan Anda berada di sana; tidak ada doa khusus wajib untuk setiap putaran.',
      tr: 'Bakara 2:201’deki duadır. Kaynak, tavafta Rükn-i Yemânî ile Hacerülesved arasında okunmasını anlatır. Yapıyı görmek orada olduğunuzu kanıtlamaz; her tur için zorunlu özel bir dua yoktur.',
    },
  },
  {
    id: 'tawaf-takbir', landmarkIds: ['black_stone'], sourceIds: ['binbaz-tawaf-dhikr'],
    arabicText: 'اللَّهُ أَكْبَرُ',
    titles: { ar: 'التكبير عند محاذاة الحجر الأسود', en: 'Takbir when level with the Black Stone', ur: 'حجر اسود کے مقابل تکبیر', id: 'Takbir saat sejajar Hajar Aswad', tr: 'Hacerülesved hizasında tekbir' },
    translations: { ar: 'المعنى: الله أكبر من كل شيء.', en: 'Meaning: Allah is the Greatest.', ur: 'مفہوم: اللہ سب سے بڑا ہے۔', id: 'Makna: Allah Mahabesar.', tr: 'Anlamı: Allah en büyüktür.' },
    notes: {
      ar: 'يذكر المرجع التكبير عند محاذاة الحجر في الطواف. ظهور الحجر في الصورة لا يحدد المحاذاة أو عدد الأشواط، ولا يطلب منك الاقتراب أو مزاحمة الناس.',
      en: 'The reference describes takbir when level with the Black Stone in Tawaf. Its appearance in an image does not establish alignment or your circuit count, and does not ask you to approach or crowd others.',
      ur: 'مرجع میں طواف کے دوران حجر اسود کے مقابل تکبیر کا ذکر ہے۔ تصویر محاذات یا چکروں کی تعداد ثابت نہیں کرتی، اور آپ کو قریب جانے یا بھیڑ کرنے کے لیے نہیں کہتی۔',
      id: 'Rujukan menjelaskan takbir saat sejajar Hajar Aswad dalam tawaf. Gambar tidak memastikan posisi atau jumlah putaran, dan tidak meminta Anda mendekat atau berdesakan.',
      tr: 'Kaynak, tavafta Hacerülesved hizasında tekbiri anlatır. Görüntü hizayı veya tur sayısını belirlemez; yaklaşmanızı ya da insanları sıkıştırmanızı istemez.',
    },
  },
  {
    id: 'safa-marwa-quran-2-158', landmarkIds: ['safa_marwa'], sourceIds: ['quran-2-158', 'binbaz-safa-marwa-dhikr'],
    arabicText: 'إِنَّ الصَّفَا وَالْمَرْوَةَ مِن شَعَائِرِ اللَّهِ',
    titles: { ar: 'تذكير من القرآن عند بدء السعي', en: 'A Quran reminder at the start of Sai', ur: 'سعی کے آغاز پر قرآنی یاد دہانی', id: 'Pengingat Al-Quran saat memulai sai', tr: 'Sa‘ye başlarken Kur’an hatırlatması' },
    translations: {
      ar: 'المعنى: الصفا والمروة من معالم عبادة الله.',
      en: 'Meaning: Safa and Marwa are among the symbols of worship established by Allah.',
      ur: 'مفہوم: صفا اور مروہ اللہ کی عبادت کی نشانیوں میں سے ہیں۔',
      id: 'Makna: Safa dan Marwah termasuk tanda-tanda ibadah kepada Allah.',
      tr: 'Anlamı: Safa ve Merve, Allah’ın ibadet alametlerindendir.',
    },
    notes: {
      ar: 'مقتطف من البقرة ١٥٨؛ يذكر المرجع قراءته عند البدء بالصفا. تصنيف المسعى هنا يجمع الصفا والمروة ولا يميز بينهما أو يحدد أنك في بداية السعي.',
      en: 'Excerpt from Al-Baqarah 2:158; the reference describes reading it when starting at Safa. This combined Masa’a category does not distinguish Safa from Marwa or establish that your Sai is starting.',
      ur: 'البقرہ ۲:۱۵۸ کا اقتباس؛ مرجع میں صفا سے آغاز پر اسے پڑھنے کا ذکر ہے۔ مشترک مسعیٰ شناخت صفا اور مروہ میں فرق یا سعی کے آغاز کی تصدیق نہیں کرتی۔',
      id: 'Petikan Al-Baqarah 2:158; rujukan menyebut pembacaannya saat memulai di Safa. Kategori Masa’a ini tidak membedakan Safa dari Marwah atau memastikan awal sai Anda.',
      tr: 'Bakara 2:158’den bir bölüm; kaynak Safa’da başlarken okunmasını anlatır. Ortak Mes‘â sınıfı Safa ile Merve’yi ayırmaz veya sa‘yin başladığını belirlemez.',
    },
  },
  {
    id: 'safa-marwa-dhikr', landmarkIds: ['safa_marwa'], sourceIds: ['binbaz-safa-marwa-dhikr'],
    arabicText: 'لا إله إلا الله وحده لا شريك له، له الملك وله الحمد، وهو على كل شيء قدير. لا إله إلا الله وحده، أنجز وعده، ونصر عبده، وهزم الأحزاب وحده.',
    titles: { ar: 'ذكر على الصفا والمروة', en: 'Dhikr at Safa and Marwa', ur: 'صفا اور مروہ پر ذکر', id: 'Zikir di Safa dan Marwah', tr: 'Safa ve Merve’de zikir' },
    translations: {
      ar: 'المعنى: لا معبود بحق إلا الله وحده؛ له الملك والحمد والقدرة، وقد وفّى بوعده ونصر عبده وهزم الأحزاب وحده.',
      en: 'Meaning: None is worthy of worship except Allah alone, without partner. His is the dominion and praise, and He has power over everything. Allah alone fulfilled His promise, aided His servant and defeated the confederates.',
      ur: 'مفہوم: اللہ اکیلے کے سوا کوئی عبادت کے لائق نہیں، اس کا کوئی شریک نہیں۔ بادشاہی اور حمد اسی کی ہے اور وہ ہر چیز پر قادر ہے۔ اس نے اپنا وعدہ پورا کیا، اپنے بندے کی مدد کی اور لشکروں کو اکیلے شکست دی۔',
      id: 'Makna: Tiada yang berhak disembah selain Allah semata, tanpa sekutu. Milik-Nya kerajaan dan pujian; Dia Mahakuasa atas segala sesuatu. Allah sendiri memenuhi janji-Nya, menolong hamba-Nya, dan mengalahkan pasukan sekutu.',
      tr: 'Anlamı: Yalnız Allah ibadete layıktır; ortağı yoktur. Mülk ve hamd O’nundur, her şeye gücü yeter. Yalnız Allah vaadini yerine getirdi, kuluna yardım etti ve birleşik düşman ordularını yenilgiye uğrattı.',
    },
    notes: {
      ar: 'ذكر وارد في حديث جابر كما يشرحه المرجع، على الصفا وعلى المروة، مع الدعاء بين تكراره. ليس دعاءً خاصاً برقم شوط؛ الصورة لا تؤكد أنك على أحد الموضعين.',
      en: 'Dhikr reported in Jabir’s hadith, as explained by the reference, at Safa and Marwa with supplication between repetitions. It is not a prayer assigned to a numbered length; an image does not confirm that you are at either endpoint.',
      ur: 'مرجع کے مطابق حدیث جابر میں صفا اور مروہ پر یہ ذکر، تکرار کے درمیان دعا کے ساتھ آیا ہے۔ یہ کسی نمبر کے چکر کی مخصوص دعا نہیں؛ تصویر کسی سرے پر موجودگی ثابت نہیں کرتی۔',
      id: 'Zikir dalam hadis Jabir, sebagaimana dijelaskan rujukan, di Safa dan Marwah dengan doa di antara pengulangan. Bukan doa untuk nomor putaran tertentu; gambar tidak memastikan Anda berada di salah satu ujung.',
      tr: 'Kaynağın açıkladığı Câbir hadisinde Safa ve Merve’de, tekrarlar arasında dua ile aktarılan zikirdir. Belirli numaralı tura atanmış dua değildir; görüntü iki uçtan birinde olduğunuzu doğrulamaz.',
    },
  },
]);

export const CURATED_SOURCES = Object.freeze([
  ...LANDMARKS.map(item => ({ id: item.sourceId, title: item.sourceTitle, url: item.url })),
  { id: 'haramain-ritual-guide', title: 'التحقيق والإيضاح في مسائل الحج والعمرة — رسالة الحرمين', url: 'https://risala.prh.gov.sa/ar/contents/209' },
  { id: 'nusuk-umrah-journey', title: 'Umrah Journey — Nusuk', url: 'https://umrah.nusuk.sa/Journey' },
  { id: 'quran-2-125', title: 'البقرة ١٢٥ — القرآن الكريم، جامعة الملك سعود', url: 'https://quran.ksu.edu.sa/tafseer/katheer/sura2-aya125.html' },
  { id: 'quran-2-158', title: 'البقرة ١٥٨ — القرآن الكريم، جامعة الملك سعود', url: 'https://quran.ksu.edu.sa/tafseer/katheer/sura2-aya158.html' },
  { id: 'quran-2-201', title: 'البقرة ٢٠١ — القرآن الكريم، جامعة الملك سعود', url: 'https://quran.ksu.edu.sa/tafseer/saadi/sura2-aya201.html' },
  { id: 'binbaz-tawaf-dhikr', title: 'الأدعية والأذكار المشروعة في الطواف والسعي — الموقع الرسمي للشيخ ابن باز', url: 'https://binbaz.org.sa/fatwas/10450/الادعية-والاذكار-المشروعة-في-الطواف-والسعي' },
  { id: 'binbaz-safa-marwa-dhikr', title: 'من حديث أبدأ بما بدأ الله به — الموقع الرسمي للشيخ ابن باز', url: 'https://binbaz.org.sa/audios/51/7--من-حديث-ابدا-بما-بدا-الله-به' },
]);

export function landmarkById(id) { return LANDMARKS.find(item => item.id === id); }
export function devotionalsForLandmark(id, language) {
  if (!landmarkById(id) || !VISION_LANGUAGES.includes(language)) return [];
  return DEVOTIONALS.filter(item => item.landmarkIds.includes(id)).map(item => ({
    id: item.id, title: item.titles[language], arabicText: item.arabicText,
    translation: item.translations[language], note: item.notes[language], sourceIds: [...item.sourceIds],
  }));
}
export function devotionalContext(language, landmarkId = null) {
  // This lookup chooses only static records. A client ID narrows context, never proves a location.
  return DEVOTIONALS.filter(item => !landmarkId || item.landmarkIds.includes(landmarkId)).map(item => ({
    id: item.id, landmarkIds: [...item.landmarkIds], title: item.titles[language], arabicText: item.arabicText,
    translation: item.translations[language], note: item.notes[language], sourceIds: [...item.sourceIds],
  }));
}
export function sourceById(id) {
  const source = CURATED_SOURCES.find(item => item.id === id);
  return source ? { ...source } : null;
}

export const VISION_COPY = Object.freeze({
  ar: { uncertain: 'لم أتعرف على عنصر بوضوح. جرّب صورة أوضح يظهر فيها المعلم كاملاً.', summary: 'عناصر تعرّفت عليها. اختر عنصراً لمعرفة المزيد.', possible: 'قد يكون: ', fact: 'المعلومة المرجعية: ', visual: 'وصف بصري تقديري: ', translation: 'ترجمة مقترحة للنص: ', text: 'قراءة آلية للنص؛ تحقّق من اللوحة الأصلية.', answer: 'قراءة الصورة تقديرية. ' },
  en: { uncertain: 'No item was identified clearly. Try a clearer image showing the whole landmark.', summary: 'Recognized items. Select one to learn more.', possible: 'Possibly: ', fact: 'Reference information: ', visual: 'Approximate visual description: ', translation: 'Suggested text translation: ', text: 'Machine-read text; check the original sign.', answer: 'The image reading is approximate. ' },
  ur: { uncertain: 'کوئی چیز واضح طور پر پہچانی نہیں گئی۔ ایسی واضح تصویر آزمائیں جس میں پوری نشانی نظر آئے۔', summary: 'پہچانی گئی چیزیں۔ مزید جاننے کے لیے ایک منتخب کریں۔', possible: 'ممکنہ شناخت: ', fact: 'حوالہ جاتی معلومات: ', visual: 'تخمینی بصری وضاحت: ', translation: 'متن کا مجوزہ ترجمہ: ', text: 'متن مشینی طور پر پڑھا گیا ہے؛ اصل تختی دیکھیں۔', answer: 'تصویر کی شناخت تخمینی ہے۔ ' },
  id: { uncertain: 'Belum ada objek yang dikenali dengan jelas. Coba gambar lebih jelas yang menampilkan seluruh penanda.', summary: 'Objek yang dikenali. Pilih satu untuk mengetahui lebih lanjut.', possible: 'Kemungkinan: ', fact: 'Informasi rujukan: ', visual: 'Deskripsi visual perkiraan: ', translation: 'Usulan terjemahan teks: ', text: 'Teks dibaca mesin; periksa papan aslinya.', answer: 'Pembacaan gambar bersifat perkiraan. ' },
  tr: { uncertain: 'Bir öğe net olarak tanınamadı. Yapının tamamını gösteren daha net bir görüntü deneyin.', summary: 'Tanımlanan öğeler. Daha fazla bilgi için birini seçin.', possible: 'Olası eşleşme: ', fact: 'Kaynak bilgisi: ', visual: 'Yaklaşık görsel açıklama: ', translation: 'Önerilen metin çevirisi: ', text: 'Metin makineyle okundu; asıl tabelayı kontrol edin.', answer: 'Görüntü yorumu yaklaşıktır. ' },
});
