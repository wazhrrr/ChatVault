package com.ganyu.chatvault.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ganyu.chatvault.data.Attachment
import com.ganyu.chatvault.data.Conversation
import com.ganyu.chatvault.data.Importer
import com.ganyu.chatvault.data.Message
import com.ganyu.chatvault.data.Prefs
import com.ganyu.chatvault.data.SearchHit
import com.ganyu.chatvault.data.SortMode
import com.ganyu.chatvault.data.StatsBundle
import com.ganyu.chatvault.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { CHATS, SEARCH, STATS, FILES, SETTINGS }

data class UiState(
    val loading: Boolean = true,
    val conversations: List<Conversation> = emptyList(),
    val totalConvs: Int = 0,
    val totalMsgs: Int = 0,
    val totalAtts: Int = 0,
    val screen: Screen = Screen.CHATS,
    val selectedId: String? = null,
    val selected: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val attachments: Map<String, List<Attachment>> = emptyMap(),
    val convAttachments: List<Attachment> = emptyList(),
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val starredOnly: Boolean = false,
    val year: Int? = null,
    val sort: SortMode = SortMode.NEWEST,
    val stats: StatsBundle? = null,
    val allFiles: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    val phase: String = "",
    val progress: Float = 0f,
    val toast: String? = null,
    val jumpMessageId: String? = null,
    val themeMode: Int = 1,
    val fontScale: Float = 1f,
    val codeWrap: Boolean = true
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    val prefs = Prefs(app)
    private val importer = Importer(app, store)

    private val _state = MutableStateFlow(
        UiState(
            themeMode = prefs.themeMode,
            fontScale = prefs.fontScale,
            codeWrap = prefs.codeWrap
        )
    )
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (list, triple) = withContext(Dispatchers.IO) {
                val s = _state.value
                store.listConversations(s.starredOnly, s.year, s.sort) to store.counts()
            }
            _state.update {
                it.copy(
                    loading = false,
                    conversations = list,
                    totalConvs = triple.first,
                    totalMsgs = triple.second,
                    totalAtts = triple.third
                )
            }
            if (_state.value.screen == Screen.STATS) loadStats()
        }
    }

    fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        when (screen) {
            Screen.STATS -> loadStats()
            Screen.FILES -> loadFiles()
            else -> {}
        }
    }

    fun openConversation(id: String) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val conv = store.conversation(id)
                val msgs = store.messages(id)
                val atts = store.attachmentsOfConv(id)
                Triple(conv, msgs, atts)
            }
            _state.update {
                it.copy(
                    selectedId = id,
                    selected = data.first,
                    messages = data.second,
                    convAttachments = data.third,
                    attachments = data.third.groupBy { a -> a.msgId ?: "" },
                    jumpMessageId = null
                )
            }
        }
    }

    fun closeConversation() {
        _state.update { it.copy(selectedId = null, selected = null, messages = emptyList(), attachments = emptyMap(), convAttachments = emptyList()) }
    }

    fun jumpToMessage(id: String) {
        _state.update { it.copy(jumpMessageId = id) }
    }

    fun consumeJump() {
        _state.update { it.copy(jumpMessageId = null) }
    }

    fun toggleStar() {
        val conv = _state.value.selected ?: return
        val next = !conv.starred
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setStar(conv.id, next) }
            _state.update {
                it.copy(
                    selected = it.selected?.copy(starred = next),
                    conversations = it.conversations.map { c -> if (c.id == conv.id) c.copy(starred = next) else c }
                )
            }
        }
    }

    fun setStarredOnly(v: Boolean) {
        _state.update { it.copy(starredOnly = v) }
        refresh()
    }

    fun setYear(v: Int?) {
        _state.update { it.copy(year = v) }
        refresh()
        loadStats()
    }

    fun setSort(v: SortMode) {
        _state.update { it.copy(sort = v) }
        refresh()
    }

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(hits = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            delay(220)
            val hits = withContext(Dispatchers.IO) { store.search(q) }
            _state.update { it.copy(hits = hits, searching = false) }
        }
    }

    suspend fun searchInConv(convId: String, q: String): List<SearchHit> =
        withContext(Dispatchers.IO) { if (q.isBlank()) emptyList() else store.searchInConv(convId, q) }

    fun loadStats() {
        viewModelScope.launch {
            val year = _state.value.year
            val s = withContext(Dispatchers.IO) { store.stats(year) }
            _state.update { it.copy(stats = s) }
        }
    }

    fun loadFiles() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { store.allAttachments() }
            _state.update { it.copy(allFiles = files) }
        }
    }

    fun importPackage(uri: Uri, force: Boolean = false, takePermission: Boolean = true) {
        if (_state.value.importing) return
        if (takePermission) {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(importing = true, phase = "准备导入", progress = 0f) }
            try {
                val summary = importer.importFrom(uri, force) { p ->
                    _state.update {
                        it.copy(
                            phase = p.phase,
                            progress = if (p.total > 0) p.done.toFloat() / p.total else 0f
                        )
                    }
                }
                _state.update {
                    it.copy(
                        importing = false,
                        progress = 1f,
                        toast = "导入完成：新增 ${summary.added} · 更新 ${summary.updated} · 跳过 ${summary.skipped}，" +
                            "共 ${summary.messages} 条消息、${summary.attachments} 个附件" +
                            if (summary.missing > 0) "（${summary.missing} 个附件缺失）" else ""
                    )
                }
                refresh()
                loadStats()
                val id = _state.value.selectedId
                if (id != null) openConversation(id)
            } catch (t: Throwable) {
                _state.update { it.copy(importing = false, toast = "导入失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clearAll()
                getApplication<Application>().filesDir.resolve("blobs").deleteRecursively()
            }
            _state.update {
                it.copy(selectedId = null, selected = null, messages = emptyList(), conversations = emptyList(),
                    stats = null, allFiles = emptyList(), totalConvs = 0, totalMsgs = 0, totalAtts = 0,
                    toast = "已清空本地数据")
            }
            refresh()
        }
    }

    fun toast(msg: String?) {
        _state.update { it.copy(toast = msg) }
    }

    fun setThemeMode(mode: Int) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setFontScale(scale: Float) {
        prefs.fontScale = scale
        _state.update { it.copy(fontScale = scale) }
    }

    fun setCodeWrap(v: Boolean) {
        prefs.codeWrap = v
        _state.update { it.copy(codeWrap = v) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }
}
