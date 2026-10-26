export type DemoLanguage = 'ar' | 'en' | 'ur' | 'id' | 'tr';
export type DemoMode = 'tawaf' | 'sai';
export type DemoStep = 0 | 1 | 2 | 3;

export interface DemoState {
  step: DemoStep;
  playing: boolean;
  language: DemoLanguage;
  landmarkOpen: boolean;
  answerVisible: boolean;
  mode: DemoMode;
  counts: Record<DemoMode, number>;
  history: Record<DemoMode, number[]>;
}

export const initialDemoState = (): DemoState => ({
  step: 0, playing: false, language: 'ar', landmarkOpen: false, answerVisible: false,
  mode: 'tawaf', counts: { tawaf: 0, sai: 0 }, history: { tawaf: [], sai: [] },
});

export type DemoAction =
  | { type: 'step'; step: number }
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'tick' }
  | { type: 'landmark' }
  | { type: 'language'; language: DemoLanguage }
  | { type: 'question' }
  | { type: 'mode'; mode: DemoMode }
  | { type: 'increment' }
  | { type: 'undo' }
  | { type: 'reset-counter' }
  | { type: 'reset' };

export function demoReducer(state: DemoState, action: DemoAction): DemoState {
  switch (action.type) {
    case 'step': return { ...state, step: Math.min(3, Math.max(0, action.step)) as DemoStep, playing: false };
    case 'play': return { ...state, step: state.step === 3 ? 0 : state.step, playing: true };
    case 'pause': return { ...state, playing: false };
    case 'tick': return !state.playing ? state : state.step === 3
      ? { ...state, playing: false }
      : { ...state, step: (state.step + 1) as DemoStep, playing: state.step < 2 };
    case 'landmark': return { ...state, landmarkOpen: !state.landmarkOpen, playing: false };
    case 'language': return { ...state, language: action.language, answerVisible: false, playing: false };
    case 'question': return { ...state, answerVisible: true, playing: false };
    case 'mode': return { ...state, mode: action.mode, playing: false };
    case 'increment': {
      const count = state.counts[state.mode];
      if (count === 7) return { ...state, playing: false };
      return { ...state, playing: false, counts: { ...state.counts, [state.mode]: count + 1 },
        history: { ...state.history, [state.mode]: [...state.history[state.mode], count].slice(-20) } };
    }
    case 'undo': {
      const history = state.history[state.mode];
      if (!history.length) return { ...state, playing: false };
      return { ...state, playing: false, counts: { ...state.counts, [state.mode]: history[history.length - 1] },
        history: { ...state.history, [state.mode]: history.slice(0, -1) } };
    }
    case 'reset-counter': return { ...state, playing: false,
      counts: { ...state.counts, [state.mode]: 0 }, history: { ...state.history, [state.mode]: [] } };
    case 'reset': return initialDemoState();
  }
}

export const demoTranslations: Record<DemoLanguage, {
  name: string; exit: string; landmark: string; question: string; answer: string;
}> = {
  ar: { name: 'العربية', exit: 'خروج', landmark: 'الكعبة المشرفة', question: 'ما هذا المعلم؟',
    answer: 'يمثّل هذا الرسم الكعبة المشرفة. في التطبيق، يمكنك فتح وصف المعلم ومراجعة مصادر المعلومات.' },
  en: { name: 'English', exit: 'Exit', landmark: 'The Kaaba', question: 'What landmark is this?',
    answer: 'This illustration represents the Kaaba. In the app, open a landmark to read its description and check its sources.' },
  ur: { name: 'اردو', exit: 'باہر نکلیں', landmark: 'خانہ کعبہ', question: 'یہ کون سا مقام ہے؟',
    answer: 'یہ خاکہ خانہ کعبہ کی نمائندگی کرتا ہے۔ ایپ میں مقام کی تفصیل اور معلومات کے ذرائع دیکھے جا سکتے ہیں۔' },
  id: { name: 'Indonesia', exit: 'Keluar', landmark: 'Ka’bah', question: 'Bangunan apa ini?',
    answer: 'Ilustrasi ini menggambarkan Ka’bah. Di aplikasi, buka tempat yang dikenali untuk membaca deskripsi dan sumbernya.' },
  tr: { name: 'Türkçe', exit: 'Çıkış', landmark: 'Kâbe', question: 'Bu yapı nedir?',
    answer: 'Bu çizim Kâbe’yi temsil eder. Uygulamada bir yapıyı açarak açıklamasını ve bilgi kaynaklarını inceleyebilirsiniz.' },
};
