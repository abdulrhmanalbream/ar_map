import Foundation

let companionLanguages: [(code: String, label: String)] = [
    ("ar", "العربية"), ("en", "English"), ("ur", "اردو"), ("id", "Indonesia"), ("tr", "Türkçe"),
]

func companionIsRTL(_ language: String) -> Bool { language == "ar" || language == "ur" }

/// The companion follows the user's selected language, including its controls.
/// iPhone has only the phone camera source; Eye-specific variants are not carried over.
struct VisionCopy {
    let language: String
    var importedImage: Bool = false

    private func t(_ ar: String, _ en: String, _ ur: String, _ id: String, _ tr: String) -> String {
        switch language {
        case "ar": return ar
        case "ur": return ur
        case "id": return id
        case "tr": return tr
        default: return en
        }
    }

    var title: String { t("المطوف الذكي", "Smart Mutawwif", "سمارٹ مطوف", "Mutawwif Cerdas", "Akıllı Mutavvıf") }
    var chooseLanguage: String { t("اختر لغتك", "Choose your language", "اپنی زبان منتخب کریں", "Pilih bahasa Anda", "Dilinizi seçin") }
    var languageIntro: String { t("لغة الشرح والمحادثة. يمكنك تغييرها لاحقاً من الإعدادات.", "Choose the language for explanations and conversation. You can change it in settings.", "وضاحت اور گفتگو کی زبان منتخب کریں۔ اسے بعد میں ترتیبات میں بدل سکتے ہیں۔", "Pilih bahasa penjelasan dan percakapan. Anda dapat mengubahnya di pengaturan.", "Açıklama ve konuşma dilini seçin. Ayarlardan değiştirebilirsiniz.") }
    var continueLabel: String { t("متابعة", "Continue", "جاری رکھیں", "Lanjutkan", "Devam") }
    var chooseImage: String { t("اختيار صورة", "Choose a photo", "تصویر منتخب کریں", "Pilih foto", "Fotoğraf seç") }
    var imageLoading: String { t("تجهيز الصورة…", "Preparing photo…", "تصویر تیار ہو رہی ہے…", "Menyiapkan foto…", "Fotoğraf hazırlanıyor…") }
    var imageError: String { t("تعذر فتح الصورة؛ جرّب صورة أخرى.", "Could not open this photo. Try another image.", "تصویر نہیں کھل سکی۔ دوسری تصویر آزمائیں۔", "Foto tidak dapat dibuka. Coba gambar lain.", "Fotoğraf açılamadı. Başka bir görüntü deneyin.") }
    var importedSource: String { t("صورة مختارة", "Selected photo", "منتخب تصویر", "Foto pilihan", "Seçilen fotoğraf") }
    var returnToCamera: String { t("العودة للكاميرا", "Return to camera", "کیمرے پر واپس", "Kembali ke kamera", "Kameraya dön") }
    var photoReady: String { t("الصورة جاهزة؛ تعرّف عليها أو اسأل عنها.", "Your photo is ready. Identify it or ask about it.", "تصویر تیار ہے۔ اسے پہچانیں یا اس کے بارے میں پوچھیں۔", "Foto siap. Kenali atau tanyakan tentangnya.", "Fotoğraf hazır. Tanımlayın veya hakkında sorun.") }
    var cameraPermissionTitle: String { t("اختر كيف تبدأ", "Choose how to start", "شروع کرنے کا طریقہ منتخب کریں", "Pilih cara memulai", "Nasıl başlayacağınızı seçin") }
    var cameraPermissionNote: String { t("اسمح بالكاميرا لاستخدام الجوال، أو اختر صورة من جهازك. إرسال الصور إلى المساعد الذكي يحتاج موافقتك داخل المرافق.", "Allow camera access, or choose a photo from your device. Sharing images with Smart Assistant requires your consent in the companion.", "کیمرے کی اجازت دیں، یا آلے سے تصویر منتخب کریں۔ ذہین معاون کو تصویر بھیجنے کے لیے معاون میں آپ کی اجازت ضروری ہے۔", "Izinkan kamera, atau pilih foto perangkat. Berbagi gambar dengan Asisten Cerdas memerlukan persetujuan Anda di pendamping.", "Kameraya izin verin ya da cihazdan fotoğraf seçin. Akıllı Asistan ile görüntü paylaşımı için rehberde onayınız gerekir.") }
    var allowCamera: String { t("السماح بالكاميرا", "Allow camera", "کیمرے کی اجازت", "Izinkan kamera", "Kameraya izin ver") }
    var openAppSettings: String { t("فتح إعدادات التطبيق", "Open app settings", "ایپ کی ترتیبات کھولیں", "Buka pengaturan aplikasi", "Uygulama ayarlarını aç") }
    var muteNarration: String { t("كتم الصوت", "Mute audio", "آواز بند کریں", "Bisukan suara", "Sesi kapat") }
    var unmuteNarration: String { t("تشغيل الصوت", "Unmute audio", "آواز چلائیں", "Aktifkan suara", "Sesi aç") }
    var serviceBusy: String { t("المساعد الذكي مشغول مؤقتاً؛ أعد المحاولة بعد قليل.", "Smart Assistant is temporarily busy. Try again shortly.", "ذہین معاون عارضی طور پر مصروف ہے۔ کچھ دیر بعد کوشش کریں۔", "Asisten Cerdas sedang sibuk. Coba lagi sebentar.", "Akıllı Asistan geçici olarak meşgul. Biraz sonra deneyin.") }
    var serviceTimeout: String { t("انتهت مهلة الطلب السابق؛ أعد المحاولة.", "The previous request timed out. Try again.", "پچھلے سوال کا وقت ختم ہو گیا۔ دوبارہ کوشش کریں۔", "Permintaan sebelumnya kehabisan waktu. Coba lagi.", "Önceki isteğin süresi doldu. Tekrar deneyin.") }
    var uploadTimeout: String { t("لم يكتمل رفع الصورة في الوقت المتاح. تحقق من اتصالك ثم أعد المحاولة.", "The image upload did not finish in time. Check your connection, then try again.", "تصویر مقررہ وقت میں اپ لوڈ نہیں ہوئی۔ رابطہ چیک کر کے دوبارہ کوشش کریں۔", "Unggahan gambar belum selesai tepat waktu. Periksa koneksi, lalu coba lagi.", "Görüntü yükleme zamanında tamamlanmadı. Bağlantınızı kontrol edip tekrar deneyin.") }
    func retryWaiting(_ seconds: Int64) -> String { t("أعد المحاولة بعد \(seconds) ثانية. لم نرسل طلباً جديداً.", "Try again in \(seconds) seconds. No new request was sent.", "\(seconds) سیکنڈ بعد دوبارہ کوشش کریں۔ کوئی نیا سوال نہیں بھیجا گیا۔", "Coba lagi dalam \(seconds) detik. Tidak ada permintaan baru yang dikirim.", "\(seconds) saniye sonra tekrar deneyin. Yeni istek gönderilmedi.") }
    var serviceUnavailable: String { t("خدمة الشرح غير متاحة الآن؛ جرّب لاحقاً.", "Explanations are unavailable right now. Try later.", "وضاحت کی سروس ابھی دستیاب نہیں۔ بعد میں کوشش کریں۔", "Layanan penjelasan belum tersedia. Coba nanti.", "Açıklama hizmeti şu anda kullanılamıyor. Sonra deneyin.") }
    var serviceConfiguration: String { t("خدمة الشرح تحتاج تحديث إعدادات السيرفر. تواصل مع المشرف.", "The explanation service needs a server configuration update. Contact the administrator.", "وضاحت کی سروس کے سرور کی ترتیبات درست کرنا ضروری ہے۔ منتظم سے رابطہ کریں۔", "Layanan penjelasan memerlukan pembaruan konfigurasi server. Hubungi admin.", "Açıklama hizmetinin sunucu ayarları güncellenmeli. Yöneticiyle iletişime geçin.") }
    var responseBlocked: String { t("لم تتمكن الخدمة من الإجابة عن هذه الصورة؛ جرّب صورة أو سؤالاً آخر.", "The service could not answer about this image. Try another photo or question.", "سروس اس تصویر کا جواب نہیں دے سکی۔ دوسری تصویر یا سوال آزمائیں۔", "Layanan tidak dapat menjawab gambar ini. Coba foto atau pertanyaan lain.", "Hizmet bu görüntüyü yanıtlayamadı. Başka fotoğraf veya soru deneyin.") }
    var identify: String { t("تعرّف الآن", "Identify now", "اب پہچانیں", "Kenali sekarang", "Şimdi tanı") }
    var enable: String { t("تفعيل المرافق", "Enable companion", "معاون فعال کریں", "Aktifkan pendamping", "Rehberi etkinleştir") }
    var settings: String { t("الإعدادات", "Settings", "ترتیبات", "Pengaturan", "Ayarlar") }
    var talkShort: String { t("اسأل بالصوت", "Ask by voice", "آواز سے پوچھیں", "Tanya lewat suara", "Sesle sor") }
    var cameraSource: String { importedImage ? importedSource : t("كاميرا الجوال", "Phone camera", "فون کا کیمرا", "Kamera ponsel", "Telefon kamerası") }
    var pause: String { t("إيقاف مؤقت", "Pause", "روکیں", "Jeda", "Duraklat") }
    var resume: String { t("متابعة التعرف", "Resume", "دوبارہ شروع", "Lanjutkan", "Devam et") }
    var off: String { t("التعرف متوقف", "Recognition is off", "شناخت بند ہے", "Pengenalan nonaktif", "Tanıma kapalı") }
    var automatic: String { t("تعرف تلقائي", "Automatic recognition", "خودکار شناخت", "Pengenalan otomatis", "Otomatik tanıma") }
    var manualRecognition: String { t("تعرف يدوي", "Manual recognition", "دستی شناخت", "Pengenalan manual", "Elle tanıma") }
    var examining: String { t("المساعد الذكي يحلل اللقطة المرسلة…", "Smart Assistant is analyzing the sent snapshot…", "ذہین معاون بھیجی گئی تصویر کا تجزیہ کر رہا ہے…", "Asisten Cerdas menganalisis cuplikan yang dikirim…", "Akıllı Asistan gönderilen görüntüyü inceliyor…") }
    var answering: String { t("المساعد الذكي يجهّز الرد على سؤالك…", "Smart Assistant is preparing your answer…", "ذہین معاون آپ کے سوال کا جواب تیار کر رہا ہے…", "Asisten Cerdas menyiapkan jawaban Anda…", "Akıllı Asistan yanıtınızı hazırlıyor…") }
    var requestPending: String { t("الطلب قيد المعالجة…", "Processing request…", "درخواست پر کام جاری ہے…", "Memproses permintaan…", "İstek işleniyor…") }
    var resultTitle: String { t("نتيجة اللقطة", "Snapshot result", "تصویر کا نتیجہ", "Hasil cuplikan", "Görüntü sonucu") }
    var resultReady: String { t("النتيجة جاهزة؛ اضغط عنصراً لمعرفة المزيد.", "Result ready. Tap an item to learn more.", "نتیجہ تیار ہے۔ مزید جاننے کے لیے کسی چیز پر دبائیں۔", "Hasil siap. Ketuk item untuk mengetahui lebih lanjut.", "Sonuç hazır. Ayrıntılar için bir öğeye dokunun.") }
    var ready: String { t("وجّه كاميرا الجوال إلى المعلم وثبّت الهاتف لحظات", "Point the phone camera at a landmark and hold the phone still briefly", "فون کا کیمرا نشانی کی طرف کریں اور فون کو کچھ لمحے ساکن رکھیں", "Arahkan kamera ponsel ke penanda dan tahan ponsel sebentar", "Telefon kamerasını yapıya yöneltin ve telefonu kısa süre sabit tutun") }
    var purpose: String { t("تعرّف على المعالم واقرأ اللوحات بلغتك", "Discover landmarks and read signs in your language", "مقامات پہچانیں اور اپنی زبان میں تختیاں پڑھیں", "Kenali tempat dan baca papan dalam bahasa Anda", "Yapıları tanıyın, tabelaları dilinizde okuyun") }
    var review: String { t("عرض نتيجة اللقطة", "View snapshot result", "تصویر کا نتیجہ دیکھیں", "Lihat hasil cuplikan", "Görüntü sonucunu aç") }
    var snapshot: String { t("لقطة محفوظة مؤقتاً · ليست بثاً مباشراً", "Temporary snapshot · not live video", "عارضی تصویر · براہ راست ویڈیو نہیں", "Cuplikan sementara · bukan siaran langsung", "Geçici görüntü · canlı yayın değil") }
    var estimate: String { t("التعرف تقديري؛ تأكد من المعلم قبل الاعتماد على المعلومات.", "Recognition is an estimate. Check the landmark before relying on its information.", "شناخت تخمینی ہے۔ معلومات پر اعتماد سے پہلے مقام کی تصدیق کریں۔", "Pengenalan bersifat perkiraan. Pastikan tempatnya sebelum mengandalkan informasi.", "Tanıma tahminidir. Bilgiye güvenmeden önce yapıyı kontrol edin.") }
    var ask: String { t("اسأل عن هذا المشهد", "Ask about this scene", "اس منظر کے بارے میں پوچھیں", "Tanya tentang pemandangan ini", "Bu görüntüyü sor") }
    var question: String { t("اكتب سؤالك", "Type your question", "اپنا سوال لکھیں", "Tulis pertanyaan", "Sorunuzu yazın") }
    var send: String { t("اسأل المساعد الذكي", "Ask Smart Assistant", "ذہین معاون سے پوچھیں", "Tanya Asisten Cerdas", "Akıllı Asistana sor") }
    var finishSpeaking: String { t("إنهاء الكلام", "Finish speaking", "بات مکمل کریں", "Selesai bicara", "Konuşmayı bitir") }
    var recognizingSpeech: String { t("جارٍ فهم كلامك…", "Recognizing your words…", "آپ کی بات سمجھی جا رہی ہے…", "Mengenali ucapan Anda…", "Söyledikleriniz anlaşılıyor…") }
    var speakingReply: String { t("يقرأ المرافق الرد…", "Reading the answer…", "معاون جواب پڑھ رہا ہے…", "Membacakan jawaban…", "Yanıt okunuyor…") }
    var tapToTalk: String { t("اضغط وتحدث؛ يُرسل سؤالك تلقائياً بعد كلامك.", "Tap and speak. Your question is sent when you finish.", "دبائیں اور بولیں۔ بات مکمل ہونے پر سوال بھیج دیا جائے گا۔", "Ketuk lalu bicara. Pertanyaan dikirim setelah Anda selesai.", "Dokunup konuşun. Bitirdiğinizde sorunuz gönderilir.") }
    var conversation: String { t("محادثتك مع المرافق", "Your conversation", "معاون سے گفتگو", "Percakapan Anda", "Görüşmeniz") }
    var closeConversation: String { t("إنهاء المحادثة", "End conversation", "گفتگو ختم کریں", "Akhiri percakapan", "Görüşmeyi bitir") }
    var devotionalTitle: String { t("أذكار وتذكير", "Remembrance and reminders", "اذکار اور یاد دہانی", "Zikir dan pengingat", "Zikir ve hatırlatmalar") }
    var readArabic: String { t("قراءة النص بالعربية", "Read the Arabic text", "عربی متن سنیں", "Bacakan teks Arab", "Arapça metni oku") }
    var listening: String { t("أستمع…", "Listening…", "سن رہا ہوں…", "Mendengarkan…", "Dinleniyor…") }
    var read: String { t("اقرأ الرد", "Read aloud", "جواب سنیں", "Bacakan", "Sesli oku") }
    var stop: String { t("إيقاف الصوت", "Stop audio", "آواز بند", "Hentikan suara", "Sesi durdur") }
    var speechPermission: String { t("اسمح باستخدام المايك والتعرف على الكلام ثم اضغط للتحدث. يمكنك كتابة سؤالك أيضاً.", "Allow microphone and speech recognition, then tap to speak. You can also type your question.", "مائیک اور آواز کی شناخت کی اجازت دیں، پھر بولنے کے لیے دبائیں۔ آپ سوال لکھ بھی سکتے ہیں۔", "Izinkan mikrofon dan pengenalan ucapan, lalu ketuk untuk bicara. Anda juga dapat mengetik pertanyaan.", "Mikrofon ve konuşma tanımaya izin verip konuşmaya dokunun. Sorunuzu yazabilirsiniz de.") }
    var speechNotHeard: String { t("لم أسمع كلاماً واضحاً. اضغط المايك وحاول مرة أخرى.", "I did not catch that. Tap the microphone and try again.", "بات واضح نہیں سنائی دی۔ مائیک دبائیں اور دوبارہ کوشش کریں۔", "Ucapan belum jelas. Ketuk mikrofon dan coba lagi.", "Söylediklerinizi duyamadım. Mikrofona dokunup tekrar deneyin.") }
    var speechLanguageUnavailable: String { t("التعرف على الكلام بهذه اللغة غير متاح على الجهاز. يمكنك الكتابة وقراءة الرد.", "Speech recognition for this language is unavailable on the device. You can type and read the answer.", "اس زبان کی آواز کی شناخت آلے پر دستیاب نہیں۔ سوال لکھیں اور جواب پڑھیں۔", "Pengenalan ucapan bahasa ini tidak tersedia di perangkat. Anda dapat mengetik dan membaca jawaban.", "Bu dilde konuşma tanıma cihazda kullanılamıyor. Yazabilir ve yanıtı okuyabilirsiniz.") }
    var voiceFrameUnavailable: String { t("انتظر عودة صورة الكاميرا، ثم اضغط المايك مرة أخرى.", "Wait for the camera image, then tap the microphone again.", "کیمرے کی تصویر واپس آنے دیں، پھر مائیک دبائیں۔", "Tunggu gambar kamera kembali, lalu ketuk mikrofon lagi.", "Kamera görüntüsünü bekleyip mikrofona yeniden dokunun.") }
    var close: String { t("إغلاق", "Close", "بند کریں", "Tutup", "Kapat") }
    var live: String { t("العودة للبث", "Back to live", "براہ راست واپس", "Kembali ke siaran", "Canlıya dön") }
    var sources: String { t("مصادر المعلومات", "Information sources", "معلومات کے ذرائع", "Sumber informasi", "Bilgi kaynakları") }
    var languageLabel: String { t("لغتك", "Your language", "آپ کی زبان", "Bahasa Anda", "Diliniz") }
    var translate: String { t("ترجمة النصوص الظاهرة", "Translate visible text", "نظر آنے والے متن کا ترجمہ", "Terjemahkan teks terlihat", "Görünen metni çevir") }
    var translateNote: String { t("تظهر ترجمة اللوحة ضمن العناصر عند وضوح النص.", "A sign's translation appears as an item when its text is legible.", "واضح متن والی تختی کا ترجمہ ایک عنصر کے طور پر ظاہر ہوتا ہے۔", "Terjemahan papan muncul sebagai item jika teks terbaca.", "Okunaklı tabelaların çevirisi öğe olarak görünür.") }
    var sharing: String { t("مشاركة لقطات مع المساعد الذكي", "Share snapshots with Smart Assistant", "ذہین معاون کو تصاویر بھیجیں", "Bagikan cuplikan ke Asisten Cerdas", "Görüntüleri Akıllı Asistan ile paylaş") }
    var privacy: String {
        if importedImage {
            return t("تبقى الصورة المختارة محلياً حتى تفعّل المشاركة. عند طلب شرحها أو السؤال عنها، تُرسل الصورة والنص إلى المساعد الذكي عبر سيرفر المشروع. يُقرأ الشرح صوتياً ويمكنك كتمه. لا يحفظ التطبيق أو السيرفر الصورة؛ قد تطبق شروط Google. المايك يعمل عند ضغطك فقط، ويمكن إيقاف المشاركة من الإعدادات.",
                     "The selected photo stays local until sharing is enabled. When you request an explanation or ask about it, its image and text go to Smart Assistant through our server. Explanations are spoken and can be muted. The app and server do not save the photo; Google's terms may apply. The microphone starts only when tapped. You can stop sharing in settings.",
                     "منتخب تصویر اشتراک فعال کرنے تک آلے پر رہتی ہے۔ وضاحت یا سوال پر تصویر اور متن ہمارے سرور سے ذہین معاون کو بھیجے جاتے ہیں۔ وضاحت آواز میں سنائی جاتی ہے اور اسے خاموش کر سکتے ہیں۔ ایپ اور سرور تصویر محفوظ نہیں کرتے؛ Google کی شرائط لاگو ہو سکتی ہیں۔ مائیک دبانے پر شروع ہوتا ہے۔ ترتیبات میں اشتراک بند کر سکتے ہیں۔",
                     "Foto tetap lokal sampai berbagi diaktifkan. Saat meminta penjelasan atau bertanya, gambar dan teks dikirim ke Asisten Cerdas melalui server kami. Penjelasan dibacakan dan dapat dibisukan. Aplikasi dan server tidak menyimpan foto; ketentuan Google dapat berlaku. Mikrofon mulai saat diketuk. Berbagi dapat dihentikan di pengaturan.",
                     "Seçilen fotoğraf, paylaşım açılana kadar cihazda kalır. Açıklama istediğinizde veya soru sorduğunuzda görüntü ve metin sunucumuz üzerinden Akıllı Asistana gönderilir. Açıklamalar seslendirilir ve susturulabilir. Uygulama ve sunucu fotoğrafı kaydetmez; Google koşulları geçerli olabilir. Mikrofon dokununca açılır. Paylaşımı ayarlardan kapatabilirsiniz.")
        }
        let source = cameraSource
        return t("بعد التفعيل، تُرسل لقطات دورية من \(source) أثناء فتح هذه الشاشة، وصورة مع كل سؤال. يعالج المساعد الذكي الصور والنص عبر سيرفر المشروع. التعرف الصوتي يستخدم خدمة الصوت التي يوفرها جهازك، والمايك يعمل عند ضغطك فقط. لا يحفظ التطبيق أو السيرفر الصور؛ قد تطبق شروط Google لمعالجة البيانات. يمكنك إيقاف المشاركة في أي وقت.",
                 "When enabled, periodic snapshots from \(source) are sent while this screen is open, plus one image with each question. Smart Assistant processes images and text through our server. Speech recognition uses your device's speech service; the microphone starts only when you tap. The app and server do not save images; Google's data terms may apply. You can stop sharing at any time.",
                 "فعال ہونے پر اس اسکرین کے کھلے رہنے کے دوران \(source) سے وقفے وقفے سے تصاویر اور ہر سوال کے ساتھ ایک تصویر بھیجی جاتی ہے۔ ذہین معاون ہمارے سرور کے ذریعے تصاویر اور متن پر کام کرتا ہے۔ آواز کی شناخت آلے کی صوتی سروس کرتی ہے؛ مائیک صرف دبانے پر شروع ہوتا ہے۔ ایپ اور سرور تصاویر محفوظ نہیں کرتے؛ Google کی ڈیٹا شرائط لاگو ہو سکتی ہیں۔ آپ کسی بھی وقت بند کر سکتے ہیں۔",
                 "Saat aktif, cuplikan berkala dari \(source) dikirim selama layar ini terbuka, beserta satu gambar untuk setiap pertanyaan. Asisten Cerdas memproses gambar dan teks melalui server kami. Pengenalan ucapan memakai layanan suara perangkat; mikrofon mulai hanya saat Anda mengetuk. Aplikasi dan server tidak menyimpan gambar; ketentuan data Google dapat berlaku. Anda dapat berhenti kapan saja.",
                 "Etkinleştirildiğinde bu ekran açıkken \(source) üzerinden aralıklı görüntüler ve her soruyla bir görüntü gönderilir. Akıllı Asistan, görüntüleri ve metni sunucumuz üzerinden işler. Ses tanıma cihazınızın konuşma hizmetini kullanır; mikrofon yalnızca dokunduğunuzda açılır. Uygulama ve sunucu görüntüleri kaydetmez; Google veri koşulları geçerli olabilir. Paylaşımı istediğiniz an durdurabilirsiniz.")
    }
    var agree: String { t("تفعيل ومشاركة اللقطات", "Enable and share snapshots", "فعال کریں اور تصاویر بھیجیں", "Aktifkan dan bagikan cuplikan", "Etkinleştir ve görüntü paylaş") }
    var connect: String { t("ربط المساعد", "Connect assistant", "معاون جوڑیں", "Hubungkan asisten", "Asistanı bağla") }
    var connectNote: String { t("انضم باسمك إلى المجموعة العامة، أو استخدم رمز مجموعة خاصة. تفعيل المساعد الذكي يحتاج موافقتك على مشاركة اللقطات بعد الربط. عدّاد الأشواط يعمل دون ربط.", "Join the public group with your name, or use a private group code. After joining, Smart Assistant still needs your consent to share snapshots. The lap counter works without joining.", "اپنے نام سے عوامی گروپ میں شامل ہوں یا نجی گروپ کا کوڈ استعمال کریں۔ شامل ہونے کے بعد بھی ذہین معاون کو تصاویر بھیجنے کے لیے آپ کی اجازت ضروری ہے۔ چکروں کا شمار گروپ کے بغیر بھی کام کرتا ہے۔", "Bergabung ke grup umum dengan nama Anda, atau gunakan kode grup pribadi. Setelah bergabung, Asisten Cerdas tetap memerlukan persetujuan untuk berbagi cuplikan. Penghitung putaran bekerja tanpa bergabung.", "Adınızla genel gruba katılın veya özel grup kodunu kullanın. Katıldıktan sonra Akıllı Asistan için görüntü paylaşma onayınız gerekir. Tur sayacı katılmadan da çalışır.") }
    var name: String { t("اسمك", "Your name", "آپ کا نام", "Nama Anda", "Adınız") }
    var privateCode: String { t("رمز مجموعة خاصة (اختياري)", "Private group code (optional)", "نجی گروپ کوڈ (اختیاری)", "Kode grup pribadi (opsional)", "Özel grup kodu (isteğe bağlı)") }
    var joinPublic: String { t("الانضمام للمجموعة العامة", "Join the public group", "عوامی گروپ میں شامل ہوں", "Gabung grup umum", "Genel gruba katıl") }
    var joinPrivate: String { t("الانضمام بالرمز", "Join with code", "کوڈ سے شامل ہوں", "Gabung dengan kode", "Kodla katıl") }
    var joined: String { t("مرتبط بالمجموعة", "Joined group", "گروپ سے منسلک", "Tergabung di grup", "Gruba bağlı") }
    var leave: String { t("إلغاء الربط", "Disconnect", "رابطہ ختم کریں", "Putuskan", "Bağlantıyı kes") }
    var noItems: String { t("لم يظهر عنصر واضح للتعرف عليه. قرّب المعلم أو اللوحة وجرّب لقطة جديدة.", "No clear item was recognized. Get a clearer view of the landmark or sign and try again.", "کوئی واضح چیز نہیں پہچانی گئی۔ مقام یا تختی کو واضح کر کے دوبارہ کوشش کریں۔", "Belum ada item yang jelas. Arahkan lebih jelas ke tempat atau papan lalu coba lagi.", "Net bir öğe tanınmadı. Yapıyı veya tabelayı daha net gösterip tekrar deneyin.") }
    var freshFrame: String { t("بانتظار لقطة جديدة من كاميرا الجوال", "Waiting for a fresh phone camera frame", "فون کے کیمرے کی نئی تصویر کا انتظار ہے", "Menunggu cuplikan baru dari kamera ponsel", "Telefon kamerasından yeni görüntü bekleniyor") }
    var rateLimited: String { t("الخدمة مشغولة؛ انتظر قليلاً ثم أعد المحاولة", "The service is busy. Wait a moment and try again.", "سروس مصروف ہے۔ کچھ دیر انتظار کر کے دوبارہ کوشش کریں۔", "Layanan sedang sibuk. Tunggu sebentar lalu coba lagi.", "Hizmet meşgul. Biraz bekleyip tekrar deneyin.") }
    var networkError: String { t("تعذر الوصول إلى المساعد الذكي؛ تحقق من الإنترنت وأعد المحاولة", "Cannot reach Smart Assistant. Check your connection and try again.", "ذہین معاون سے رابطہ نہیں ہو سکا۔ انٹرنیٹ چیک کر کے دوبارہ کوشش کریں۔", "Tidak dapat menghubungi Asisten Cerdas. Periksa koneksi lalu coba lagi.", "Akıllı Asistana ulaşılamıyor. İnternet bağlantınızı kontrol edip tekrar deneyin.") }
    var invalidResponse: String { t("لم تصل قراءة موثوقة؛ جرّب لقطة أوضح", "No valid reading was returned. Try a clearer snapshot.", "قابل اعتماد نتیجہ موصول نہیں ہوا۔ زیادہ واضح تصویر سے کوشش کریں۔", "Hasil yang valid belum diterima. Coba cuplikan yang lebih jelas.", "Geçerli bir sonuç alınamadı. Daha net bir görüntüyle deneyin.") }
    var imageEncodingError: String { t("تعذر تجهيز اللقطة؛ أعد المحاولة", "Could not prepare the snapshot. Please try again.", "تصویر تیار نہیں ہو سکی۔ دوبارہ کوشش کریں۔", "Cuplikan tidak dapat disiapkan. Silakan coba lagi.", "Görüntü hazırlanamadı. Lütfen tekrar deneyin.") }
    var consentRequired: String { t("فعّل مشاركة اللقطات أولاً من الإعدادات.", "Enable snapshot sharing in settings first.", "پہلے ترتیبات میں تصاویر کا اشتراک فعال کریں۔", "Aktifkan berbagi cuplikan di pengaturan terlebih dahulu.", "Önce ayarlardan görüntü paylaşımını etkinleştirin.") }
    var enrollmentRequired: String { t("اربط المساعد بالمجموعة أولاً.", "Connect the assistant to a group first.", "پہلے معاون کو گروپ سے جوڑیں۔", "Hubungkan asisten ke grup terlebih dahulu.", "Önce asistanı bir gruba bağlayın.") }
    var authentication: String { t("انتهى الربط؛ انضم للمجموعة مرة أخرى.", "The connection expired. Join the group again.", "رابطہ ختم ہو گیا۔ گروپ میں دوبارہ شامل ہوں۔", "Koneksi berakhir. Gabung ke grup lagi.", "Bağlantı sona erdi. Gruba yeniden katılın.") }
    var emptyQuestion: String { t("اكتب سؤالاً أو تحدث أولاً.", "Type or speak a question first.", "پہلے سوال لکھیں یا بولیں۔", "Ketik atau ucapkan pertanyaan dahulu.", "Önce bir soru yazın veya söyleyin.") }
    var sceneRequired: String { t("التقط لقطة أولاً ثم اسأل عنها.", "Capture a snapshot first, then ask about it.", "پہلے تصویر لیں، پھر اس کے بارے میں پوچھیں۔", "Ambil cuplikan dahulu, lalu tanyakan.", "Önce bir görüntü alın, sonra sorun.") }
    var cameraUnavailable: String { t("الكاميرا غير متاحة الآن.", "The camera is unavailable right now.", "کیمرا ابھی دستیاب نہیں۔", "Kamera tidak tersedia saat ini.", "Kamera şu anda kullanılamıyor.") }
    var counter: String { t("عدّاد الأشواط", "Lap counter", "چکروں کی گنتی", "Penghitung putaran", "Tur sayacı") }

    func message(for error: VisionError) -> String {
        switch error {
        case .enrollmentRequired: return enrollmentRequired
        case .cameraUnavailable: return cameraUnavailable
        case .frameStale: return freshFrame
        case .consentRequired: return consentRequired
        case .emptyQuestion: return emptyQuestion
        case .sceneRequired: return sceneRequired
        case .network: return networkError
        case .rateLimit: return rateLimited
        case .authentication: return authentication
        case .invalidResponse: return invalidResponse
        case .imageEncoding: return imageEncodingError
        case .serviceBusy: return serviceBusy
        case .timeout: return serviceTimeout
        case .uploadTimeout: return uploadTimeout
        case .serviceUnavailable: return serviceUnavailable
        case .serviceConfiguration, .serviceAuthentication, .modelUnavailable: return serviceConfiguration
        case .responseBlocked: return responseBlocked
        }
    }
}
