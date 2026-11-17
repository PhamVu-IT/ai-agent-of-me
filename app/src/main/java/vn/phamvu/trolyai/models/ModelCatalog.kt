package vn.phamvu.trolyai.models

/**
 * Một mô hình tải được từ Hugging Face. Danh sách lấy từ model_allowlists/1_0_12.json của
 * Google AI Edge Gallery (Apache-2.0), chỉ giữ các mô hình trò chuyện chạy được bằng LiteRT-LM.
 * Tải theo đúng [commit] Gallery đã thử, để file không bị đổi ngầm.
 */
data class CatalogModel(
    val name: String,
    val repo: String,
    val file: String,
    val commit: String,
    val sizeBytes: Long,
    /** RAM tối thiểu Gallery khuyên dùng (GB). */
    val minRamGb: Int,
    val image: Boolean = false,
    val audio: Boolean = false,
    val thinking: Boolean = false,
    /** Mô hình của Google cần đăng nhập Hugging Face và đồng ý điều khoản trước khi tải. */
    val gated: Boolean = false,
    val note: String = "",
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$file?download=true"
    val pageUrl: String get() = "https://huggingface.co/$repo"
    val sizeMb: Long get() = sizeBytes / 1_000_000

    companion object {
        val ALL = listOf(
            CatalogModel("Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
                "7fa1d78473894f7e736a21d920c3aa80f950c0db", 2_583_085_056, 8,
                image = true, audio = true, thinking = true,
                note = "Khuyên dùng: nhanh, hiểu ảnh và âm thanh, có chế độ suy nghĩ."),
            CatalogModel("Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
                "9695417f248178c63a9f318c6e0c56cb917cb837", 3_654_467_584, 12,
                image = true, audio = true, thinking = true,
                note = "Thông minh hơn E2B nhưng chậm hơn, cần máy nhiều RAM."),
            CatalogModel("Gemma 3n E2B", "google/gemma-3n-E2B-it-litert-lm", "gemma-3n-E2B-it-int4.litertlm",
                "ba9ca88da013b537b6ed38108be609b8db1c3a16", 3_655_827_456, 8,
                image = true, audio = true, gated = true),
            CatalogModel("Gemma 3n E4B", "google/gemma-3n-E4B-it-litert-lm", "gemma-3n-E4B-it-int4.litertlm",
                "297ed75955702dec3503e00c2c2ecbbf475300bc", 4_919_541_760, 12,
                image = true, audio = true, gated = true),
            CatalogModel("Gemma 3 1B", "litert-community/Gemma3-1B-IT", "gemma3-1b-it-int4.litertlm",
                "42d538a932e8d5b12e6b3b455f5572560bd60b2c", 584_417_280, 6, gated = true,
                note = "Nhỏ và rất nhanh, nhưng tiếng Việt kém hơn."),
            CatalogModel("Qwen 2.5 1.5B", "litert-community/Qwen2.5-1.5B-Instruct",
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "19edb84c69a0212f29a6ef17ba0d6f278b6a1614", 1_597_931_520, 6),
            CatalogModel("DeepSeek R1 Distill 1.5B", "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                "e34bb88632342d1f9640bad579a45134eb1cf988", 1_833_451_520, 6,
                note = "Hay suy luận dài trước khi trả lời."),
        )

        fun find(name: String) = ALL.firstOrNull { it.name == name }
    }
}
