package com.chami.pdfmaker

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Phase 6 — app language support.
 * Language codes: en, hi, es, fr, de, pt, ar, zh, ru, ja.
 * The chosen language is stored in SharedPreferences "chami_prefs" under "app_lang".
 * Tool names on the Home tiles stay in English (they are product names);
 * section headers, bottom-nav labels and Settings titles use [t].
 */
object LocaleHelper {

  const val PREFS = "chami_prefs"
  const val KEY_LANG = "app_lang"

  val LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "English",
    "hi" to "हिन्दी",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "pt" to "Português",
    "ar" to "العربية",
    "zh" to "中文",
    "ru" to "Русский",
    "ja" to "日本語"
  )

  fun localeFor(code: String): Locale = when (code) {
    "hi" -> Locale("hi")
    "es" -> Locale("es")
    "fr" -> Locale("fr")
    "de" -> Locale("de")
    "pt" -> Locale("pt")
    "ar" -> Locale("ar")
    "zh" -> Locale.SIMPLIFIED_CHINESE
    "ru" -> Locale("ru")
    "ja" -> Locale.JAPANESE
    else -> Locale.ENGLISH
  }

  fun currentCode(context: Context): String {
    val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_LANG, "en") ?: "en"
    return if (LANGUAGES.any { it.first == code }) code else "en"
  }

  /** Apply the saved language to this context's resources (no recreate). Call in onCreate before setContent. */
  fun applyLocale(context: Context) {
    val locale = localeFor(currentCode(context))
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
  }

  /** Persist the language, apply it, and recreate the activity so every screen switches immediately. */
  fun setLocale(context: Context, languageCode: String) {
    val code = if (LANGUAGES.any { it.first == languageCode }) languageCode else "en"
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY_LANG, code).apply()
    val locale = localeFor(code)
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
    if (context is Activity) context.recreate()
  }

  private val STRINGS: Map<String, Map<String, String>> = mapOf(
    "en" to mapOf(
      "home" to "Home", "history" to "History", "settings" to "Settings",
      "simple_tools" to "SIMPLE TOOLS", "smart_tools" to "SMART TOOLS",
      "images_to_pdf" to "Images to PDF", "scan_to_pdf" to "Scan to PDF",
      "merge_pdf" to "Merge PDF", "split_pdf" to "Split PDF",
      "protect_pdf" to "Protect PDF", "compress_kb" to "Compress KB",
      "pdf_to_images" to "PDF to Images", "image_resizer" to "Image Resizer",
      "pdf_reader" to "PDF Reader", "format_convert" to "Format Convert",
      "ocr" to "OCR", "convert" to "Convert", "save_pdf" to "Save PDF",
      "done" to "Done", "language" to "Language",
      "contact_support" to "Contact Support", "check_updates" to "Check for Updates",
      "storage" to "Storage", "app_lock" to "App Lock", "background" to "Background",
      "font_size" to "Font Size", "clear_cache" to "Clear Cache",
      "privacy_policy" to "Privacy Policy"
    ),
    "hi" to mapOf(
      "home" to "होम", "history" to "इतिहास", "settings" to "सेटिंग्स",
      "simple_tools" to "सरल टूल्स", "smart_tools" to "स्मार्ट टूल्स",
      "images_to_pdf" to "इमेज से PDF", "scan_to_pdf" to "स्कैन से PDF",
      "merge_pdf" to "PDF मिलाएँ", "split_pdf" to "PDF अलग करें",
      "protect_pdf" to "PDF सुरक्षित करें", "compress_kb" to "KB कम करें",
      "pdf_to_images" to "PDF से इमेज", "image_resizer" to "इमेज रिसाइज़र",
      "pdf_reader" to "PDF रीडर", "format_convert" to "फ़ॉर्मेट बदलें",
      "ocr" to "OCR", "convert" to "बदलें", "save_pdf" to "PDF सहेजें",
      "done" to "हो गया", "language" to "भाषा",
      "contact_support" to "सहायता से संपर्क करें", "check_updates" to "अपडेट देखें",
      "storage" to "स्टोरेज", "app_lock" to "ऐप लॉक", "background" to "बैकग्राउंड",
      "font_size" to "फ़ॉन्ट आकार", "clear_cache" to "कैश साफ़ करें",
      "privacy_policy" to "गोपनीयता नीति"
    ),
    "es" to mapOf(
      "home" to "Inicio", "history" to "Historial", "settings" to "Ajustes",
      "simple_tools" to "HERRAMIENTAS SIMPLES", "smart_tools" to "HERRAMIENTAS INTELIGENTES",
      "images_to_pdf" to "Imágenes a PDF", "scan_to_pdf" to "Escanear a PDF",
      "merge_pdf" to "Unir PDF", "split_pdf" to "Dividir PDF",
      "protect_pdf" to "Proteger PDF", "compress_kb" to "Comprimir KB",
      "pdf_to_images" to "PDF a imágenes", "image_resizer" to "Redimensionar imagen",
      "pdf_reader" to "Lector de PDF", "format_convert" to "Convertir formato",
      "ocr" to "OCR", "convert" to "Convertir", "save_pdf" to "Guardar PDF",
      "done" to "Listo", "language" to "Idioma",
      "contact_support" to "Contactar soporte", "check_updates" to "Buscar actualizaciones",
      "storage" to "Almacenamiento", "app_lock" to "Bloqueo de app", "background" to "Fondo",
      "font_size" to "Tamaño de fuente", "clear_cache" to "Borrar caché",
      "privacy_policy" to "Política de privacidad"
    ),
    "fr" to mapOf(
      "home" to "Accueil", "history" to "Historique", "settings" to "Réglages",
      "simple_tools" to "OUTILS SIMPLES", "smart_tools" to "OUTILS INTELLIGENTS",
      "images_to_pdf" to "Images en PDF", "scan_to_pdf" to "Scanner en PDF",
      "merge_pdf" to "Fusionner PDF", "split_pdf" to "Diviser PDF",
      "protect_pdf" to "Protéger PDF", "compress_kb" to "Compresser Ko",
      "pdf_to_images" to "PDF en images", "image_resizer" to "Redimensionner image",
      "pdf_reader" to "Lecteur PDF", "format_convert" to "Convertir le format",
      "ocr" to "OCR", "convert" to "Convertir", "save_pdf" to "Enregistrer PDF",
      "done" to "Terminé", "language" to "Langue",
      "contact_support" to "Contacter l'assistance", "check_updates" to "Vérifier les mises à jour",
      "storage" to "Stockage", "app_lock" to "Verrouillage de l'app", "background" to "Arrière-plan",
      "font_size" to "Taille de police", "clear_cache" to "Vider le cache",
      "privacy_policy" to "Politique de confidentialité"
    ),
    "de" to mapOf(
      "home" to "Start", "history" to "Verlauf", "settings" to "Einstellungen",
      "simple_tools" to "EINFACHE TOOLS", "smart_tools" to "SMARTE TOOLS",
      "images_to_pdf" to "Bilder zu PDF", "scan_to_pdf" to "Scannen zu PDF",
      "merge_pdf" to "PDF zusammenführen", "split_pdf" to "PDF teilen",
      "protect_pdf" to "PDF schützen", "compress_kb" to "KB komprimieren",
      "pdf_to_images" to "PDF zu Bildern", "image_resizer" to "Bildgröße ändern",
      "pdf_reader" to "PDF-Reader", "format_convert" to "Format konvertieren",
      "ocr" to "OCR", "convert" to "Konvertieren", "save_pdf" to "PDF speichern",
      "done" to "Fertig", "language" to "Sprache",
      "contact_support" to "Support kontaktieren", "check_updates" to "Nach Updates suchen",
      "storage" to "Speicher", "app_lock" to "App-Sperre", "background" to "Hintergrund",
      "font_size" to "Schriftgröße", "clear_cache" to "Cache leeren",
      "privacy_policy" to "Datenschutzerklärung"
    ),
    "pt" to mapOf(
      "home" to "Início", "history" to "Histórico", "settings" to "Configurações",
      "simple_tools" to "FERRAMENTAS SIMPLES", "smart_tools" to "FERRAMENTAS INTELIGENTES",
      "images_to_pdf" to "Imagens para PDF", "scan_to_pdf" to "Digitalizar para PDF",
      "merge_pdf" to "Unir PDF", "split_pdf" to "Dividir PDF",
      "protect_pdf" to "Proteger PDF", "compress_kb" to "Compactar KB",
      "pdf_to_images" to "PDF para imagens", "image_resizer" to "Redimensionar imagem",
      "pdf_reader" to "Leitor de PDF", "format_convert" to "Converter formato",
      "ocr" to "OCR", "convert" to "Converter", "save_pdf" to "Salvar PDF",
      "done" to "Concluído", "language" to "Idioma",
      "contact_support" to "Falar com o suporte", "check_updates" to "Verificar atualizações",
      "storage" to "Armazenamento", "app_lock" to "Bloqueio do app", "background" to "Fundo",
      "font_size" to "Tamanho da fonte", "clear_cache" to "Limpar cache",
      "privacy_policy" to "Política de privacidade"
    ),
    "ar" to mapOf(
      "home" to "الرئيسية", "history" to "السجل", "settings" to "الإعدادات",
      "simple_tools" to "أدوات بسيطة", "smart_tools" to "أدوات ذكية",
      "images_to_pdf" to "صور إلى PDF", "scan_to_pdf" to "مسح إلى PDF",
      "merge_pdf" to "دمج PDF", "split_pdf" to "تقسيم PDF",
      "protect_pdf" to "حماية PDF", "compress_kb" to "ضغط KB",
      "pdf_to_images" to "PDF إلى صور", "image_resizer" to "تغيير حجم الصورة",
      "pdf_reader" to "قارئ PDF", "format_convert" to "تحويل الصيغة",
      "ocr" to "OCR", "convert" to "تحويل", "save_pdf" to "حفظ PDF",
      "done" to "تم", "language" to "اللغة",
      "contact_support" to "الاتصال بالدعم", "check_updates" to "التحقق من التحديثات",
      "storage" to "التخزين", "app_lock" to "قفل التطبيق", "background" to "الخلفية",
      "font_size" to "حجم الخط", "clear_cache" to "مسح ذاكرة التخزين المؤقت",
      "privacy_policy" to "سياسة الخصوصية"
    ),
    "zh" to mapOf(
      "home" to "首页", "history" to "历史记录", "settings" to "设置",
      "simple_tools" to "简单工具", "smart_tools" to "智能工具",
      "images_to_pdf" to "图片转 PDF", "scan_to_pdf" to "扫描转 PDF",
      "merge_pdf" to "合并 PDF", "split_pdf" to "拆分 PDF",
      "protect_pdf" to "保护 PDF", "compress_kb" to "压缩 KB",
      "pdf_to_images" to "PDF 转图片", "image_resizer" to "图片尺寸调整",
      "pdf_reader" to "PDF 阅读器", "format_convert" to "格式转换",
      "ocr" to "OCR", "convert" to "转换", "save_pdf" to "保存 PDF",
      "done" to "完成", "language" to "语言",
      "contact_support" to "联系客服", "check_updates" to "检查更新",
      "storage" to "存储空间", "app_lock" to "应用锁", "background" to "背景",
      "font_size" to "字体大小", "clear_cache" to "清除缓存",
      "privacy_policy" to "隐私政策"
    ),
    "ru" to mapOf(
      "home" to "Главная", "history" to "История", "settings" to "Настройки",
      "simple_tools" to "ПРОСТЫЕ ИНСТРУМЕНТЫ", "smart_tools" to "УМНЫЕ ИНСТРУМЕНТЫ",
      "images_to_pdf" to "Изображения в PDF", "scan_to_pdf" to "Сканировать в PDF",
      "merge_pdf" to "Объединить PDF", "split_pdf" to "Разделить PDF",
      "protect_pdf" to "Защитить PDF", "compress_kb" to "Сжать KB",
      "pdf_to_images" to "PDF в изображения", "image_resizer" to "Изменить размер",
      "pdf_reader" to "Чтение PDF", "format_convert" to "Сменить формат",
      "ocr" to "OCR", "convert" to "Конвертировать", "save_pdf" to "Сохранить PDF",
      "done" to "Готово", "language" to "Язык",
      "contact_support" to "Связаться с поддержкой", "check_updates" to "Проверить обновления",
      "storage" to "Хранилище", "app_lock" to "Блокировка приложения", "background" to "Фон",
      "font_size" to "Размер шрифта", "clear_cache" to "Очистить кэш",
      "privacy_policy" to "Политика конфиденциальности"
    ),
    "ja" to mapOf(
      "home" to "ホーム", "history" to "履歴", "settings" to "設定",
      "simple_tools" to "シンプルツール", "smart_tools" to "スマートツール",
      "images_to_pdf" to "画像をPDFに", "scan_to_pdf" to "スキャンをPDFに",
      "merge_pdf" to "PDFを結合", "split_pdf" to "PDFを分割",
      "protect_pdf" to "PDFを保護", "compress_kb" to "KBを圧縮",
      "pdf_to_images" to "PDFを画像に", "image_resizer" to "画像リサイズ",
      "pdf_reader" to "PDFリーダー", "format_convert" to "形式変換",
      "ocr" to "OCR", "convert" to "変換", "save_pdf" to "PDFを保存",
      "done" to "完了", "language" to "言語",
      "contact_support" to "サポートに連絡", "check_updates" to "アップデートを確認",
      "storage" to "ストレージ", "app_lock" to "アプリロック", "background" to "背景",
      "font_size" to "フォントサイズ", "clear_cache" to "キャッシュを消去",
      "privacy_policy" to "プライバシーポリシー"
    )
  )

  /** Translated string for [key] in the current language; falls back to English, then the key itself. */
  fun t(context: Context, key: String): String {
    val code = currentCode(context)
    return STRINGS[code]?.get(key) ?: STRINGS["en"]?.get(key) ?: key
  }
}
