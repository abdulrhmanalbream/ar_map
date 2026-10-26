package com.sarab.vision.ritual

/** Counter copy follows the user's selected assistant language; unsupported languages use Arabic. */
internal class RitualCopy(language: String) {
    private val language = language.substringBefore('-').lowercase()
    private val index = listOf("ar", "en", "ur", "id", "tr").indexOf(this.language).coerceAtLeast(0)
    val rtl = index == 0 || index == 2
    fun text(key: String) = strings.getValue(key)[index]
    fun name(mode: String) = text(if (mode == "sai") "sai" else "tawaf")
    fun count(value: Int): String = text("count").replace("{n}", number(value))
    private fun number(value: Int) = value.toString().map {
        when { it !in '0'..'9' -> it; index == 0 -> ('٠'.code + (it - '0')).toChar()
            index == 2 -> ('۰'.code + (it - '0')).toChar(); else -> it }
    }.joinToString("")
    fun note(state: RitualCounterState, watch: RitualWatchState): String = when {
        state.source == RitualCounterSource.PHONE -> text("phoneNote")
        watch.lap?.mode != state.selectedMode -> text("watchMissing").replace("{ritual}", name(state.selectedMode))
        !watch.connected -> text("watchOffline")
        else -> text("watchNote")
    }
    fun storageError(error: String) = text(if (error.startsWith("تعذّر قراءة")) "readError" else "saveError")

    companion object {
        private val strings = mapOf(
            "tawaf" to listOf("الطواف", "Tawaf", "طواف", "Tawaf", "Tavaf"),
            "sai" to listOf("السعي", "Sa'i", "سعی", "Sai", "Sa'y"),
            "count" to listOf("{n} من ٧", "{n} of 7", "۷ میں سے {n}", "{n} dari 7", "{n} / 7"),
            "noCount" to listOf("لا يوجد عدّ", "No count", "کوئی گنتی نہیں", "Belum ada hitungan", "Sayım yok"),
            "phoneBadge" to listOf("الجوال · يدوي", "Phone · manual", "فون · دستی", "Ponsel · manual", "Telefon · manuel"),
            "watchBadge" to listOf("الساعة · آخر عدّ", "Watch · last count", "گھڑی · آخری گنتی", "Jam · hitungan terakhir", "Saat · son sayım"),
            "open" to listOf("فتح العداد والإعدادات", "Open counter and settings", "گنتی اور ترتیبات کھولیں", "Buka penghitung dan pengaturan", "Sayacı ve ayarları aç"),
            "title" to listOf("عدّاد الأشواط", "Lap counter", "چکروں کی گنتی", "Penghitung putaran", "Tur sayacı"),
            "close" to listOf("إغلاق", "Close", "بند کریں", "Tutup", "Kapat"),
            "phoneNote" to listOf("عدّ يدوي محفوظ على هذا الجوال", "Manual count saved on this phone", "اس فون پر محفوظ دستی گنتی", "Hitungan manual tersimpan di ponsel ini", "Bu telefonda kayıtlı manuel sayım"),
            "watchMissing" to listOf("لم يصل عدّ {ritual} من الساعة. افتح هذا العداد على الساعة.", "No {ritual} count received. Open this counter on your watch.", "گھڑی سے {ritual} کی گنتی موصول نہیں ہوئی۔ گھڑی پر یہ گنتی کھولیں۔", "Hitungan {ritual} belum diterima. Buka penghitung ini di jam Anda.", "{ritual} sayımı alınmadı. Saatinizde bu sayacı açın."),
            "watchOffline" to listOf("الساعة غير متصلة · آخر عدّ محفوظ، للعرض فقط", "Watch disconnected · last saved count, read-only", "گھڑی منسلک نہیں · آخری محفوظ گنتی، صرف دیکھنے کے لیے", "Jam terputus · hitungan terakhir, hanya baca", "Saat bağlı değil · son kayıtlı sayım, salt okunur"),
            "watchNote" to listOf("آخر عدّ وصل من الساعة · قد يتأخر التحديث · للعرض فقط", "Last count received from watch · updates may be delayed · read-only", "گھڑی سے آخری موصولہ گنتی · تازہ کاری میں تاخیر ہو سکتی ہے · صرف دیکھیں", "Hitungan terakhir dari jam · pembaruan dapat tertunda · hanya baca", "Saatten alınan son sayım · güncelleme gecikebilir · salt okunur"),
            "recorded" to listOf("سجّلت ٧ أشواط", "7 laps recorded", "۷ چکر درج ہو گئے", "7 putaran tercatat", "7 tur kaydedildi"),
            "increment" to listOf("سجّل شوطًا بعد إتمامه", "Record a completed lap", "مکمل چکر درج کریں", "Catat putaran yang selesai", "Tamamlanan turu kaydet"),
            "undo" to listOf("تراجع عن شوط", "Undo one lap", "ایک چکر واپس کریں", "Batalkan satu putaran", "Bir turu geri al"),
            "new" to listOf("عدّ جديد", "New count", "نئی گنتی", "Hitungan baru", "Yeni sayım"),
            "source" to listOf("مصدر العدّ المعروض", "Displayed count source", "دکھائی گئی گنتی کا ذریعہ", "Sumber hitungan", "Görünen sayımın kaynağı"),
            "phone" to listOf("هذا الجوال", "This phone", "یہ فون", "Ponsel ini", "Bu telefon"),
            "watch" to listOf("الساعة · عرض", "Watch · view", "گھڑی · صرف دیکھیں", "Jam · lihat", "Saat · görüntüle"),
            "independent" to listOf("عداد الجوال مستقل عن الساعة. تغيير المصدر لا ينقل الأشواط ولا يجمع العددين.", "Phone and watch counts are separate. Changing the source never transfers or adds their counts.", "فون اور گھڑی کی گنتی الگ ہیں۔ ذریعہ بدلنے سے گنتی منتقل یا جمع نہیں ہوتی۔", "Hitungan ponsel dan jam terpisah. Mengganti sumber tidak memindahkan atau menjumlahkan hitungan.", "Telefon ve saat sayımları ayrıdır. Kaynağı değiştirmek sayımları taşımaz veya toplamaz."),
            "manualTawaf" to listOf("سجّل الشوط بعد إتمامه. هذا العداد يحفظ تأكيدك اليدوي ولا يكتشف الأشواط تلقائيًا.", "Record each lap after completing it. This counter saves your manual confirmation; it does not detect laps automatically.", "ہر چکر مکمل کرنے کے بعد درج کریں۔ یہ آپ کی دستی تصدیق محفوظ کرتا ہے، خودکار گنتی نہیں کرتا۔", "Catat setiap putaran setelah selesai. Penghitung menyimpan konfirmasi manual Anda, bukan mendeteksi putaran otomatis.", "Her turu tamamladıktan sonra kaydedin. Sayaç manuel onayınızı saklar; turları otomatik algılamaz."),
            "manualSai" to listOf("في السعي، سجّل كل انتقال بين الصفا والمروة بعد إتمامه. العدّ هنا يدوي.", "For Sa'i, record each completed passage between Safa and Marwa. Counting here is manual.", "سعی میں صفا اور مروہ کے درمیان ہر سفر مکمل ہونے پر درج کریں۔ یہاں گنتی دستی ہے۔", "Untuk sai, catat setiap perjalanan antara Safa dan Marwa setelah selesai. Penghitungan ini manual.", "Sa'y için Safa ile Merve arasındaki her tamamlanan geçişi kaydedin. Buradaki sayım manueldir."),
            "resetTitle" to listOf("بدء عدّ جديد لـ{ritual}؟", "Start a new {ritual} count?", "{ritual} کی نئی گنتی شروع کریں؟", "Mulai hitungan {ritual} baru?", "Yeni {ritual} sayımı başlatılsın mı?"),
            "resetBody" to listOf("سيبدأ عداد الجوال من الصفر. يبقى عداد الساعة وعداد النسك الآخر كما هما.", "This phone counter will start at zero. The watch and the other ritual counter stay unchanged.", "فون کی یہ گنتی صفر سے شروع ہوگی۔ گھڑی اور دوسری عبادت کی گنتی برقرار رہے گی۔", "Penghitung ponsel ini mulai dari nol. Hitungan jam dan ibadah lainnya tetap sama.", "Bu telefon sayacı sıfırdan başlar. Saat ve diğer ibadetin sayacı değişmez."),
            "reset" to listOf("ابدأ من الصفر", "Start from zero", "صفر سے شروع کریں", "Mulai dari nol", "Sıfırdan başlat"),
            "cancel" to listOf("إلغاء", "Cancel", "منسوخ کریں", "Batal", "İptal"),
            "readError" to listOf("تعذّر قراءة العدّ المحفوظ؛ راجع عدد أشواطك قبل المتابعة.", "The saved count could not be read. Check your lap count before continuing.", "محفوظ گنتی نہیں پڑھی جا سکی۔ آگے بڑھنے سے پہلے اپنے چکر چیک کریں۔", "Hitungan tersimpan tidak dapat dibaca. Periksa jumlah putaran sebelum melanjutkan.", "Kayıtlı sayım okunamadı. Devam etmeden önce tur sayınızı kontrol edin."),
            "saveError" to listOf("لم يُحفظ التغيير. حاول مرة أخرى قبل مغادرة الشاشة.", "The change was not saved. Try again before leaving this screen.", "تبدیلی محفوظ نہیں ہوئی۔ اس اسکرین سے جانے سے پہلے دوبارہ کوشش کریں۔", "Perubahan belum tersimpan. Coba lagi sebelum meninggalkan layar ini.", "Değişiklik kaydedilmedi. Bu ekrandan ayrılmadan önce tekrar deneyin."),
        )
    }
}
