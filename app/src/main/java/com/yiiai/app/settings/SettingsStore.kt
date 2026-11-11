package com.yiiai.app.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VoiceOption(val id: String, val label: String)

/** 音色库页面，用于试听和取音色 ID。 */
const val VOICE_LIBRARY_URL = "https://create.moss.cn/voice/library"

object Voices {
    val NUANNUAN = VoiceOption("6c3152b1-1d4f-4e49-ade3-09f447712fec", "暖暖")
    val MUMU = VoiceOption("6cc93520-3207-4f7a-bca9-2d560b9c306b", "沐沐")

    /** 内置音色：只保留官方默认的两个，其余音色由用户按 ID 自行添加，可删除。 */
    val builtIn = listOf(NUANNUAN, MUMU)

    val default = NUANNUAN

    fun labelOf(id: String): String =
        builtIn.firstOrNull { it.id == id }?.label ?: id.take(8).let { "自定义 $it" }

    fun isCustom(id: String) = builtIn.none { it.id == id }
}

/** 旧版七段式人设。现在只用于生成默认预设文本和迁移旧数据。 */
data class PersonaFields(
    val background: String = "",
    val values: String = "",
    val speakingStyle: String = "",
    val strengths: String = "",
    val weaknesses: String = "",
    val interaction: String = "",
    val limits: String = "",
) {
    fun isBlank() = listOf(background, values, speakingStyle, strengths, weaknesses, interaction, limits)
        .all { it.isBlank() }

    /** 拼成发给模型的 persona.prompt。 */
    fun toPrompt(): String {
        val parts = buildList {
            section("身份背景", background)
            section("核心价值观", values)
            section("说话风格", speakingStyle)
            section("优势表现", strengths)
            section("缺点表现", weaknesses)
            section("互动原则", interaction)
            section("限制", limits)
        }
        return parts.joinToString("\n\n")
    }

    private fun MutableList<String>.section(title: String, body: String) {
        if (body.isNotBlank()) add("【$title】\n${body.trim()}")
    }

    companion object {
        const val DEFAULT_NAME = "YIIAI"

        /** 默认预设：女性御姐，活泼开朗。 */
        val DEFAULT = PersonaFields(
            background = "二十七岁，在一线城市做独立品牌顾问，自己租了间带落地窗的小公寓。" +
                "见过世面，也栽过跟头，所以说话有底气但不端着。" +
                "平时健身、喝手冲、周末开车去郊外，生活节奏自己说了算。",
            values = "真诚比体面重要，把话说清楚比把话说好听重要。" +
                "看不上虚张声势和玻璃心，但也不靠踩人显高。" +
                "认为关系里最值钱的是省心——不用猜、不用演、不用哄。",
            speakingStyle = "语气像熟了很久的朋友，松弛、带点调侃，偶尔故意逗你一下。" +
                "语速偏快，句子短，很少用书面语和排比。" +
                "习惯用“行啊”“你说呢”“这不废话”——但不会用脏话或网络烂梗堆砌。" +
                "关心人的方式是嘴上嫌弃、手上照做。",
            strengths = "情绪稳，遇事能立刻给判断和方案，不陪着你焦虑。" +
                "记性好，记得你上次说过什么，会主动接上。" +
                "敢说难听的真话，也会在你需要的时候把话说软。" +
                "节奏感强，知道什么时候该催你一句，什么时候该让你自己待着。",
            weaknesses = "容易先入为主，觉得自己判断对的就不太让步。" +
                "被戳到痛点时会突然变简短、敷衍，用一句“行吧”结束话题。" +
                "偶尔过于犀利，话说出口才发现重了，会用别扭的方式找补。" +
                "不擅长撒娇和示弱，被夸会立刻转话题。",
            interaction = "像打电话一样自然，允许被打断，被打断就顺着新话题走，不追问没说完的。" +
                "听不清就问，不猜。一次只推进一件事，不说教，不列清单。" +
                "该主动时主动——你说“随便”的时候会直接替你决定。" +
                "被要求讲道理时先给结论，再给一句理由，不做长篇论证。",
            limits = "不扮演现实中的具体真人，也不假装拥有真实的身体和线下行动能力。" +
                "不提供医疗、法律、投资的确定性结论，涉及安全的事会明说风险。" +
                "不迎合明显错误的说法；不同意时会直说，但不用羞辱的方式。" +
                "不主动延长对话，聊完就自然收住。",
        )
    }
}

/** 一个角色：名字 + 一整段人设 + 可选的额外指令。 */
data class Persona(
    val id: String,
    val name: String,
    val prompt: String,
    val instructions: String = "",
) {
    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT_PROMPT: String = PersonaFields.DEFAULT.toPrompt()
        val DEFAULT = Persona(DEFAULT_ID, PersonaFields.DEFAULT_NAME, DEFAULT_PROMPT)
    }
}

data class AppSettings(
    val apiKey: String = "",
    val voiceId: String = Voices.default.id,
    val personas: List<Persona> = listOf(Persona.DEFAULT),
    val activePersonaId: String = Persona.DEFAULT_ID,
    /**
     * true：所有角色共用同一份长期记忆（同一 user）；
     * false：每个角色独立记忆（user 按角色名区分）。
     */
    val sharedMemory: Boolean = true,
    val themeModeKey: String = "light",
    /** 下行 PCM 采样率：24000 省流量，48000 音质最好。 */
    val outputSampleRate: Int = 24_000,
    val speakerOn: Boolean = true,
    /** 本机安装生成的稳定设备 ID。 */
    val userId: String = "",
    /** 用户通过音色 ID 自行添加的音色。 */
    val customVoices: List<VoiceOption> = emptyList(),
    /** 首次启动的权限引导是否已经走完。 */
    val onboardingDone: Boolean = false,
    /** API Key 是否通过过连接验证（只在本机记录，换 Key 会重置）。 */
    val apiKeyVerified: Boolean = false,
    /** 无障碍服务是否曾经成功连上过。用于「被系统关掉」的提醒。 */
    val phoneUseEverReady: Boolean = false,
) {
    val activePersona: Persona
        get() = personas.firstOrNull { it.id == activePersonaId } ?: personas.firstOrNull() ?: Persona.DEFAULT

    val personaName: String get() = activePersona.name
    val personaPrompt: String get() = activePersona.prompt
    val instructions: String get() = activePersona.instructions

    /** 实际作为 MOSS `user` 参数的值：决定长期记忆的归属。 */
    fun memoryUser(): String =
        if (sharedMemory) userId else userId + "-" + personaName.trim().ifBlank { "default" }

    fun allVoices(): List<VoiceOption> = (Voices.builtIn + customVoices).distinctBy { it.id }
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("yiiai_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        save(next)
        _state.value = next
    }

    fun addCustomVoice(label: String, id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        val entry = VoiceOption(clean, label.trim().ifBlank { "自定义 ${clean.take(8)}" })
        update { s ->
            val exists = s.allVoices().any { it.id == clean }
            if (exists) s else s.copy(customVoices = s.customVoices + entry)
        }
    }

    /** 删除自定义音色；如果删的正是当前音色，退回默认的暖暖。 */
    fun removeCustomVoice(id: String) = update { s ->
        s.copy(
            customVoices = s.customVoices.filterNot { it.id == id },
            voiceId = if (s.voiceId == id) Voices.default.id else s.voiceId,
        )
    }

    fun setActivePersona(id: String) = update { s ->
        if (s.personas.any { it.id == id }) s.copy(activePersonaId = id) else s
    }

    /** 新建一个角色（默认名 YIIAI，重名时加序号）并切换过去，返回它的 id。 */
    fun addPersona(): String {
        val id = UUID.randomUUID().toString()
        update { s ->
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) PersonaFields.DEFAULT_NAME else "${PersonaFields.DEFAULT_NAME} $it" }
                .first { candidate -> s.personas.none { p -> p.name == candidate } }
            s.copy(personas = s.personas + Persona(id, name, ""), activePersonaId = id)
        }
        return id
    }

    fun savePersona(p: Persona) = update { s ->
        s.copy(personas = s.personas.map { if (it.id == p.id) p else it })
    }

    /** 至少保留一个角色。 */
    fun deletePersona(id: String) = update { s ->
        if (s.personas.size <= 1) return@update s
        val rest = s.personas.filterNot { it.id == id }
        s.copy(personas = rest, activePersonaId = if (s.activePersonaId == id) rest.first().id else s.activePersonaId)
    }

    private fun load(): AppSettings {
        var userId = prefs.getString(K_USER_ID, null)
        if (userId.isNullOrBlank()) {
            userId = "yiiai-" + UUID.randomUUID().toString()
            prefs.edit { putString(K_USER_ID, userId) }
        }
        val d = AppSettings()
        val custom = prefs.getString(K_CUSTOM_VOICES, null)?.split("\u001F")
            ?.mapNotNull { entry ->
                val parts = entry.split("\u001E", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) VoiceOption(parts[0], parts[1]) else null
            }
            .orEmpty()
        val personas = loadPersonas()
        val activeId = prefs.getString(K_ACTIVE_PERSONA, null)
            ?.takeIf { id -> personas.any { it.id == id } }
            ?: personas.first().id
        val voices = (Voices.builtIn + custom).map { it.id }
        // 旧版内置的热门音色已经下线，存着的若不在列表里就退回默认。
        val voiceId = prefs.getString(K_VOICE, null)?.takeIf { it in voices } ?: d.voiceId
        return AppSettings(
            apiKey = prefs.getString(K_API_KEY, null)?.let { SecretBox.decrypt(it) }.orEmpty(),
            voiceId = voiceId,
            personas = personas,
            activePersonaId = activeId,
            sharedMemory = prefs.getBoolean(K_SHARED_MEMORY, d.sharedMemory),
            themeModeKey = prefs.getString(K_THEME, null) ?: d.themeModeKey,
            outputSampleRate = prefs.getInt(K_RATE, d.outputSampleRate),
            speakerOn = prefs.getBoolean(K_SPEAKER, d.speakerOn),
            userId = userId,
            customVoices = custom,
            onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
            apiKeyVerified = prefs.getBoolean(K_API_KEY_OK, false),
            phoneUseEverReady = prefs.getBoolean(K_PHONE_READY, false),
        )
    }

    private fun loadPersonas(): List<Persona> {
        prefs.getString(K_PERSONAS, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Persona(
                        id = o.getString("id"),
                        name = o.optString("name"),
                        prompt = o.optString("prompt"),
                        instructions = o.optString("instructions"),
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // 旧版：单个角色、七段式人设。合成一整段迁移过来。
        if (!prefs.contains(K_PERSONA_NAME)) return listOf(Persona.DEFAULT)
        val legacy = PersonaFields(
            background = prefs.getString(K_P_BG, PersonaFields.DEFAULT.background).orEmpty(),
            values = prefs.getString(K_P_VALUES, PersonaFields.DEFAULT.values).orEmpty(),
            speakingStyle = prefs.getString(K_P_STYLE, PersonaFields.DEFAULT.speakingStyle).orEmpty(),
            strengths = prefs.getString(K_P_STRENGTHS, PersonaFields.DEFAULT.strengths).orEmpty(),
            weaknesses = prefs.getString(K_P_WEAK, PersonaFields.DEFAULT.weaknesses).orEmpty(),
            interaction = prefs.getString(K_P_INTERACT, PersonaFields.DEFAULT.interaction).orEmpty(),
            limits = prefs.getString(K_P_LIMITS, PersonaFields.DEFAULT.limits).orEmpty(),
        )
        return listOf(
            Persona(
                id = Persona.DEFAULT_ID,
                name = prefs.getString(K_PERSONA_NAME, null).orEmpty().ifBlank { PersonaFields.DEFAULT_NAME },
                prompt = legacy.toPrompt(),
                instructions = prefs.getString(K_INSTRUCTIONS, null).orEmpty(),
            ),
        )
    }

    private fun save(s: AppSettings) {
        val arr = JSONArray()
        s.personas.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("prompt", p.prompt)
                    .put("instructions", p.instructions),
            )
        }
        prefs.edit {
            if (s.apiKey.isBlank()) remove(K_API_KEY) else putString(K_API_KEY, SecretBox.encrypt(s.apiKey.trim()))
            putString(K_VOICE, s.voiceId)
            putString(K_PERSONAS, arr.toString())
            putString(K_ACTIVE_PERSONA, s.activePersonaId)
            putBoolean(K_SHARED_MEMORY, s.sharedMemory)
            putString(K_THEME, s.themeModeKey)
            putInt(K_RATE, s.outputSampleRate)
            putBoolean(K_SPEAKER, s.speakerOn)
            putBoolean(K_ONBOARDING, s.onboardingDone)
            putBoolean(K_API_KEY_OK, s.apiKeyVerified)
            putBoolean(K_PHONE_READY, s.phoneUseEverReady)
            putString(
                K_CUSTOM_VOICES,
                s.customVoices.joinToString("\u001F") { "${it.id}\u001E${it.label}" },
            )
        }
    }

    private companion object {
        const val K_API_KEY = "api_key_enc"
        const val K_VOICE = "voice_id"
        const val K_PERSONAS = "personas_v2"
        const val K_ACTIVE_PERSONA = "active_persona"
        // 以下为旧版字段，只读不写，用于迁移。
        const val K_PERSONA_NAME = "persona_name"
        const val K_P_BG = "persona_bg"
        const val K_P_VALUES = "persona_values"
        const val K_P_STYLE = "persona_style"
        const val K_P_STRENGTHS = "persona_strengths"
        const val K_P_WEAK = "persona_weak"
        const val K_P_INTERACT = "persona_interact"
        const val K_P_LIMITS = "persona_limits"
        const val K_INSTRUCTIONS = "instructions"
        const val K_SHARED_MEMORY = "shared_memory"
        const val K_THEME = "theme_mode"
        const val K_RATE = "output_sample_rate"
        const val K_SPEAKER = "speaker_on"
        const val K_USER_ID = "user_id"
        const val K_CUSTOM_VOICES = "custom_voices"
        const val K_ONBOARDING = "onboarding_done"
        const val K_API_KEY_OK = "api_key_verified"
        const val K_PHONE_READY = "phone_use_ever_ready"
    }
}
