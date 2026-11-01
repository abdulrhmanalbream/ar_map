import Foundation

/// Counter copy follows the user's selected language; unsupported languages use Arabic.
struct RitualCopy {
    private let index: Int

    init(_ language: String) {
        let base = language.split(separator: "-").first.map { String($0).lowercased() } ?? "ar"
        index = ["ar", "en", "ur", "id", "tr"].firstIndex(of: base) ?? 0
    }

    var rtl: Bool { index == 0 || index == 2 }

    func text(_ key: String) -> String { RitualCopy.strings[key]?[index] ?? key }
    func name(_ mode: String) -> String { text(mode == "sai" ? "sai" : "tawaf") }
    func count(_ value: Int) -> String { text("count").replacingOccurrences(of: "{n}", with: number(value)) }

    func number(_ value: Int) -> String {
        String(String(value).map { ch -> Character in
            guard let digit = ch.wholeNumberValue, ch.isASCII else { return ch }
            if index == 0 { return Character(UnicodeScalar(0x0660 + digit)!) }
            if index == 2 { return Character(UnicodeScalar(0x06F0 + digit)!) }
            return ch
        })
    }

    func manualNote(_ mode: String) -> String { text(mode == "sai" ? "manualSai" : "manualTawaf") }
    func resetTitle(_ mode: String) -> String { text("resetTitle").replacingOccurrences(of: "{ritual}", with: name(mode)) }
    func storageError(_ key: String) -> String { text(key == "saveError" ? "saveError" : "readError") }

    private static let strings: [String: [String]] = [
        "tawaf": ["الطواف", "Tawaf", "طواف", "Tawaf", "Tavaf"],
        "sai": ["السعي", "Sa'i", "سعی", "Sai", "Sa'y"],
        "count": ["{n} من ٧", "{n} of 7", "۷ میں سے {n}", "{n} dari 7", "{n} / 7"],
        "title": ["عدّاد الأشواط", "Lap counter", "چکروں کی گنتی", "Penghitung putaran", "Tur sayacı"],
        "close": ["إغلاق", "Close", "بند کریں", "Tutup", "Kapat"],
        "phoneNote": ["عدّ يدوي محفوظ على هذا الجوال", "Manual count saved on this phone", "اس فون پر محفوظ دستی گنتی", "Hitungan manual tersimpan di ponsel ini", "Bu telefonda kayıtlı manuel sayım"],
        "recorded": ["سجّلت ٧ أشواط", "7 laps recorded", "۷ چکر درج ہو گئے", "7 putaran tercatat", "7 tur kaydedildi"],
        "increment": ["سجّل شوطًا بعد إتمامه", "Record a completed lap", "مکمل چکر درج کریں", "Catat putaran yang selesai", "Tamamlanan turu kaydet"],
        "undo": ["تراجع عن شوط", "Undo one lap", "ایک چکر واپس کریں", "Batalkan satu putaran", "Bir turu geri al"],
        "new": ["عدّ جديد", "New count", "نئی گنتی", "Hitungan baru", "Yeni sayım"],
        "manualTawaf": ["سجّل الشوط بعد إتمامه. هذا العداد يحفظ تأكيدك اليدوي ولا يكتشف الأشواط تلقائيًا.", "Record each lap after completing it. This counter saves your manual confirmation; it does not detect laps automatically.", "ہر چکر مکمل کرنے کے بعد درج کریں۔ یہ آپ کی دستی تصدیق محفوظ کرتا ہے، خودکار گنتی نہیں کرتا۔", "Catat setiap putaran setelah selesai. Penghitung menyimpan konfirmasi manual Anda, bukan mendeteksi putaran otomatis.", "Her turu tamamladıktan sonra kaydedin. Sayaç manuel onayınızı saklar; turları otomatik algılamaz."],
        "manualSai": ["في السعي، سجّل كل انتقال بين الصفا والمروة بعد إتمامه. العدّ هنا يدوي.", "For Sa'i, record each completed passage between Safa and Marwa. Counting here is manual.", "سعی میں صفا اور مروہ کے درمیان ہر سفر مکمل ہونے پر درج کریں۔ یہاں گنتی دستی ہے۔", "Untuk sai, catat setiap perjalanan antara Safa dan Marwa setelah selesai. Penghitungan ini manual.", "Sa'y için Safa ile Merve arasındaki her tamamlanan geçişi kaydedin. Buradaki sayım manueldir."],
        "resetTitle": ["بدء عدّ جديد لـ{ritual}؟", "Start a new {ritual} count?", "{ritual} کی نئی گنتی شروع کریں؟", "Mulai hitungan {ritual} baru?", "Yeni {ritual} sayımı başlatılsın mı?"],
        "resetBody": ["سيبدأ عداد الجوال من الصفر. يبقى عداد النسك الآخر كما هو.", "This phone counter will start at zero. The other ritual counter stays unchanged.", "فون کی یہ گنتی صفر سے شروع ہوگی۔ دوسری عبادت کی گنتی برقرار رہے گی۔", "Penghitung ponsel ini mulai dari nol. Hitungan ibadah lainnya tetap sama.", "Bu telefon sayacı sıfırdan başlar. Diğer ibadetin sayacı değişmez."],
        "reset": ["ابدأ من الصفر", "Start from zero", "صفر سے شروع کریں", "Mulai dari nol", "Sıfırdan başlat"],
        "cancel": ["إلغاء", "Cancel", "منسوخ کریں", "Batal", "İptal"],
        "readError": ["تعذّر قراءة العدّ المحفوظ؛ راجع عدد أشواطك قبل المتابعة.", "The saved count could not be read. Check your lap count before continuing.", "محفوظ گنتی نہیں پڑھی جا سکی۔ آگے بڑھنے سے پہلے اپنے چکر چیک کریں۔", "Hitungan tersimpan tidak dapat dibaca. Periksa jumlah putaran sebelum melanjutkan.", "Kayıtlı sayım okunamadı. Devam etmeden önce tur sayınızı kontrol edin."],
        "saveError": ["لم يُحفظ التغيير. حاول مرة أخرى قبل مغادرة الشاشة.", "The change was not saved. Try again before leaving this screen.", "تبدیلی محفوظ نہیں ہوئی۔ اس اسکرین سے جانے سے پہلے دوبارہ کوشش کریں۔", "Perubahan belum tersimpan. Coba lagi sebelum meninggalkan layar ini.", "Değişiklik kaydedilmedi. Bu ekrandan ayrılmadan önce tekrar deneyin."],
    ]
}
