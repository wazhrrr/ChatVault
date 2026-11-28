package com.r.chatvault.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.r.chatvault.R
import com.r.chatvault.data.Attachment
import com.r.chatvault.data.ClipItem
import com.r.chatvault.data.CodeItem
import com.r.chatvault.data.Conversation
import com.r.chatvault.data.ImportReport
import com.r.chatvault.data.Importer
import com.r.chatvault.data.Message
import com.r.chatvault.data.NamedCount
import com.r.chatvault.data.Prefs
import com.r.chatvault.data.SearchHit
import com.r.chatvault.data.SortMode
import com.r.chatvault.data.StatsBundle
import com.r.chatvault.data.Store
import com.r.chatvault.data.TagCount
import com.r.chatvault.util.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { CHATS, SEARCH, CLIPS, CODE, STATS, FILES, SETTINGS }

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
    val searchTypes: Set<String> = emptySet(),
    val starredOnly: Boolean = false,
    val year: Int? = null,
    val category: String? = null,
    val model: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val sort: SortMode = SortMode.NEWEST,
    val tagFilter: String? = null,
    val tags: List<TagCount> = emptyList(),
    val filterCategories: List<String> = emptyList(),
    val filterModels: List<String> = emptyList(),
    val filterYears: List<Int> = emptyList(),
    val stats: StatsBundle? = null,
    val allFiles: List<Attachment> = emptyList(),
    val importing: Boolean = false,
    /** 0=导入 1=备份 2=恢复 */
    val progressKind: Int = 0,
    val phase: String = "",
    val progress: Float = 0f,
    val toast: String? = null,
    val jumpMessageId: String? = null,
    val jumpQuery: String? = null,
    val themeMode: Int = 1,
    val fontScale: Float = 1f,
    /** "" = 跟随系统，否则 zh / en */
    val langTag: String = "",
    val codeWrap: Boolean = true,
    val clips: List<ClipItem> = emptyList(),
    val clipQuery: String = "",
    val clipTag: String? = null,
    val clipTags: List<String> = emptyList(),
    val clipCount: Int = 0,
    val codeItems: List<CodeItem> = emptyList(),
    val codeQuery: String = "",
    val codeLang: String? = null,
    val codeLangs: List<NamedCount> = emptyList(),
    val importReport: ImportReport? = null
) {
    val filterCount: Int
        get() = listOfNotNull(year, category, model, dateFrom, dateTo).size
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    val prefs = Prefs(app)
    private val importer = Importer(app, store)
    private val backupManager = BackupManager(app, store)

    /** 非 Compose 文案：按应用内语言解析资源 */
    private fun res(resId: Int, vararg args: Any): String =
        com.r.chatvault.util.AppLocale.str(getApplication(), resId, *args)

    private val _state = MutableStateFlow(
        UiState(
            themeMode = prefs.themeMode,
            fontScale = prefs.fontScale,
            langTag = prefs.langTag,
            codeWrap = prefs.codeWrap
        )
    )
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.ensureDerivedData() }
            loadClips()
            loadCode()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val s = _state.value
                val list = store.listConversations(
                    s.starredOnly, s.year, s.sort, s.category, s.tagFilter, s.model, s.dateFrom, s.dateTo
                )
                val counts = store.counts()
                val tags = store.allTags()
                val clipCount = store.clipCount()
                val cats = store.categoryNames()
                val models = store.modelNames()
                val years = store.yearList()
                RefreshData(list, counts, tags, clipCount, cats, models, years)
            }
            _state.update {
                it.copy(
                    loading = false,
                    conversations = data.list,
                    totalConvs = data.counts.first,
                    totalMsgs = data.counts.second,
                    totalAtts = data.counts.third,
                    tags = data.tags,
                    clipCount = data.clipCount,
                    filterCategories = data.categories,
                    filterModels = data.models,
                    filterYears = data.years
                )
            }
            if (_state.value.screen == Screen.STATS) loadStats()
        }
    }

    private data class RefreshData(
        val list: List<Conversation>,
        val counts: Triple<Int, Int, Int>,
        val tags: List<TagCount>,
        val clipCount: Int,
        val categories: List<String>,
        val models: List<String>,
        val years: List<Int>
    )

    fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        when (screen) {
            Screen.STATS -> loadStats()
            Screen.FILES -> loadFiles()
            Screen.CLIPS -> loadClips()
            Screen.CODE -> loadCode()
            else -> {}
        }
    }

    fun openConversation(id: String, jumpMsgId: String? = null, jumpQuery: String? = null) {
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
                    jumpMessageId = jumpMsgId,
                    jumpQuery = jumpQuery
                )
            }
        }
    }

    fun closeConversation() {
        _state.update {
            it.copy(
                selectedId = null,
                selected = null,
                messages = emptyList(),
                attachments = emptyMap(),
                convAttachments = emptyList(),
                jumpMessageId = null,
                jumpQuery = null
            )
        }
    }

    fun jumpToMessage(id: String, query: String? = null) {
        _state.update { it.copy(jumpMessageId = id, jumpQuery = query) }
    }

    fun consumeJump() {
        _state.update { it.copy(jumpMessageId = null, jumpQuery = null) }
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

    fun setTagFilter(tag: String?) {
        _state.update { it.copy(tagFilter = tag) }
        refresh()
    }

    fun saveConvMeta(id: String, alias: String?, tags: List<String>) {
        viewModelScope.launch {
            val conv = withContext(Dispatchers.IO) {
                store.setAlias(id, alias)
                store.setTags(id, tags)
                store.conversation(id)
            }
            _state.update {
                it.copy(
                    selected = if (it.selectedId == id) conv else it.selected,
                    conversations = it.conversations.map { c -> if (c.id == id && conv != null) conv else c }
                )
            }
            refresh()
        }
    }

    fun setStar(id: String, starred: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setStar(id, starred) }
            _state.update {
                it.copy(
                    selected = if (it.selected?.id == id) it.selected.copy(starred = starred) else it.selected,
                    conversations = it.conversations.map { c ->
                        if (c.id == id) c.copy(starred = starred) else c
                    }
                )
            }
        }
    }

    fun applyFilters(
        sort: SortMode,
        year: Int?,
        category: String?,
        model: String?,
        fromTs: Long?,
        toTs: Long?
    ) {
        _state.update {
            it.copy(
                sort = sort,
                year = year,
                category = category,
                model = model,
                dateFrom = fromTs,
                dateTo = toTs
            )
        }
        refresh()
        loadStats()
    }

    fun resetFilters() {
        applyFilters(SortMode.NEWEST, null, null, null, null, null)
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteConversation(id) }
            _state.update {
                val clear = it.selectedId == id
                it.copy(
                    selectedId = if (clear) null else it.selectedId,
                    selected = if (clear) null else it.selected,
                    messages = if (clear) emptyList() else it.messages,
                    attachments = if (clear) emptyMap() else it.attachments,
                    convAttachments = if (clear) emptyList() else it.convAttachments,
                    screen = if (clear) Screen.CHATS else it.screen,
                    toast = res(R.string.toast_chat_deleted)
                )
            }
            refresh()
            loadStats()
            loadClips()
            loadCode()
            loadFiles()
        }
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
            val types = _state.value.searchTypes
            val hits = withContext(Dispatchers.IO) { store.searchAll(q, types) }
            _state.update { it.copy(hits = hits, searching = false) }
        }
    }

    fun setSearchTypes(types: Set<String>) {
        _state.update { it.copy(searchTypes = types) }
        val q = _state.value.query
        if (q.isNotBlank()) search(q)
    }

    suspend fun searchInConv(convId: String, q: String): List<SearchHit> =
        withContext(Dispatchers.IO) { if (q.isBlank()) emptyList() else store.searchInConv(convId, q) }

    fun loadStats() {
        viewModelScope.launch {
            val s = _state.value
            val stats = withContext(Dispatchers.IO) {
                store.stats(s.year, s.category, s.model, s.dateFrom, s.dateTo)
            }
            _state.update { it.copy(stats = stats) }
        }
    }

    fun loadFiles() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { store.allAttachments() }
            _state.update { it.copy(allFiles = files) }
        }
    }

    fun loadClips() {
        viewModelScope.launch {
            val s = _state.value
            val (clips, count, allTags) = withContext(Dispatchers.IO) {
                val all = store.clips(null, null)
                val tagList = all.flatMap { it.tagList }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(16).map { it.key }
                Triple(store.clips(s.clipQuery, s.clipTag), store.clipCount(), tagList)
            }
            _state.update { it.copy(clips = clips, clipCount = count, clipTags = allTags) }
        }
    }

    fun setClipQuery(q: String) {
        _state.update { it.copy(clipQuery = q) }
        loadClips()
    }

    fun setClipTag(tag: String?) {
        _state.update { it.copy(clipTag = tag) }
        loadClips()
    }

    fun addClip(convId: String?, msgId: String?, text: String, note: String, tags: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.addClip(convId, msgId, text.take(20_000), note.trim().ifBlank { null }, tags, "clip")
            }
            _state.update { it.copy(toast = res(R.string.toast_saved_clip)) }
            loadClips()
        }
    }

    fun bookmark(msg: Message) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.addClip(msg.convId, msg.id, msg.text.take(20_000), null, "", "bookmark")
            }
            _state.update { it.copy(toast = res(R.string.toast_bookmarked)) }
            loadClips()
        }
    }

    fun updateClip(id: Long, note: String, tags: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.updateClip(id, note.trim().ifBlank { null }, tags)
            }
            _state.update { it.copy(toast = res(R.string.toast_note_updated)) }
            loadClips()
        }
    }

    fun revertClip(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.revertClip(id) }
            _state.update { it.copy(toast = res(R.string.toast_reverted)) }
            loadClips()
        }
    }

    fun deleteClip(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteClip(id) }
            _state.update { it.copy(toast = res(R.string.toast_clip_deleted)) }
            loadClips()
        }
    }

    fun loadCode() {
        viewModelScope.launch {
            val s = _state.value
            val (items, langs) = withContext(Dispatchers.IO) {
                store.codeBlocks(s.codeLang, s.codeQuery) to store.codeLanguages()
            }
            _state.update { it.copy(codeItems = items, codeLangs = langs) }
        }
    }

    fun setCodeLang(lang: String?) {
        _state.update { it.copy(codeLang = lang) }
        loadCode()
    }

    fun setCodeQuery(q: String) {
        _state.update { it.copy(codeQuery = q) }
        loadCode()
    }

    suspend fun childrenOf(msgId: String): List<Message> =
        withContext(Dispatchers.IO) { store.childrenOf(msgId) }

    suspend fun branchChain(startId: String): List<Message> =
        withContext(Dispatchers.IO) { store.branchChain(startId) }

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
            _state.update { it.copy(importing = true, progressKind = 0, phase = res(R.string.toast_prepare_import), progress = 0f) }
            try {
                val summary = importer.importFrom(uri, force) { p ->
                    _state.update {
                        it.copy(
                            phase = p.phase,
                            progress = if (p.total > 0) p.done.toFloat() / p.total else 0f
                        )
                    }
                }
                val report = withContext(Dispatchers.IO) {
                    ImportReport(
                        summary = summary,
                        missing = if (summary.missing > 0) store.missingAttachments() else emptyList(),
                        orphans = if (summary.orphanFiles > 0) store.orphanAttachments() else emptyList()
                    )
                }
                _state.update {
                    it.copy(
                        importing = false,
                        progress = 1f,
                        importReport = report,
                        toast = null
                    )
                }
                android.util.Log.d(
                    "ChatVault",
                    "import done: added=${summary.added} updated=${summary.updated} skipped=${summary.skipped} " +
                        "missing=${report.missing.size} orphans=${report.orphans.size}"
                )
                refresh()
                loadStats()
                loadClips()
                loadCode()
                val id = _state.value.selectedId
                if (id != null) openConversation(id)
            } catch (t: Throwable) {
                android.util.Log.e("ChatVault", "import failed", t)
                _state.update { it.copy(importing = false, toast = res(R.string.toast_import_failed, t.message ?: res(R.string.err_unknown))) }
            }
        }
    }

    fun importMarkdown(uris: List<Uri>) {
        if (_state.value.importing || uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, progressKind = 0, phase = res(R.string.toast_prepare_md), progress = 0f) }
            try {
                val summary = importer.importMarkdown(uris) { p ->
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
                        importReport = ImportReport(summary),
                        toast = null
                    )
                }
                refresh()
                loadStats()
                loadClips()
                loadCode()
            } catch (t: Throwable) {
                _state.update { it.copy(importing = false, toast = res(R.string.toast_import_failed, t.message ?: res(R.string.err_unknown))) }
            }
        }
    }

    fun backupTo(uri: Uri) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, progressKind = 1, phase = res(R.string.toast_prepare_backup), progress = 0f) }
            try {
                val bytes = backupManager.backup(uri) { p ->
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
                        toast = res(R.string.toast_backup_done, com.r.chatvault.data.Fmt.bytes(bytes))
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(importing = false, toast = res(R.string.toast_backup_failed, t.message ?: res(R.string.err_unknown))) }
            }
        }
    }

    fun restoreFrom(uri: Uri) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, progressKind = 2, phase = res(R.string.toast_prepare_restore), progress = 0f) }
            try {
                val result = backupManager.restore(uri) { p ->
                    _state.update {
                        it.copy(
                            phase = p.phase,
                            progress = if (p.total > 0) p.done.toFloat() / p.total else 0f
                        )
                    }
                }
                // 如果恢复的是旧版本备份，补齐代码块与附件文本索引
                withContext(Dispatchers.IO) { store.ensureDerivedData() }
                _state.update {
                    it.copy(
                        importing = false,
                        progress = 1f,
                        selectedId = null,
                        selected = null,
                        messages = emptyList(),
                        attachments = emptyMap(),
                        convAttachments = emptyList(),
                        stats = null,
                        allFiles = emptyList(),
                        clips = emptyList(),
                        codeItems = emptyList(),
                        importReport = null,
                        toast = res(R.string.toast_restore_done, result.conversations, result.messages)
                    )
                }
                refresh()
                loadStats()
                loadFiles()
                loadClips()
                loadCode()
            } catch (t: Throwable) {
                _state.update { it.copy(importing = false, toast = res(R.string.toast_restore_failed, t.message ?: res(R.string.err_unknown))) }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clearAll()
                val files = getApplication<Application>().filesDir
                files.resolve("blobs").deleteRecursively()
                files.resolve("blobs.bak").deleteRecursively()
                files.resolve("backup").deleteRecursively()
            }
            _state.update {
                it.copy(
                    selectedId = null,
                    selected = null,
                    messages = emptyList(),
                    conversations = emptyList(),
                    stats = null,
                    allFiles = emptyList(),
                    clips = emptyList(),
                    codeItems = emptyList(),
                    codeLangs = emptyList(),
                    tags = emptyList(),
                    tagFilter = null,
                    year = null,
                    category = null,
                    model = null,
                    dateFrom = null,
                    dateTo = null,
                    filterCategories = emptyList(),
                    filterModels = emptyList(),
                    filterYears = emptyList(),
                    clipCount = 0,
                    importReport = null,
                    query = "",
                    hits = emptyList(),
                    totalConvs = 0,
                    totalMsgs = 0,
                    totalAtts = 0,
                    toast = res(R.string.toast_cleared)
                )
            }
            refresh()
        }
    }

    fun toast(msg: String?) {
        _state.update { it.copy(toast = msg) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun consumeImportReport() {
        _state.update { it.copy(importReport = null) }
    }

    fun setThemeMode(mode: Int) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setLanguage(tag: String) {
        prefs.langTag = tag
        _state.update { it.copy(langTag = tag) }
    }

    fun setFontScale(scale: Float) {
        prefs.fontScale = scale
        _state.update { it.copy(fontScale = scale) }
    }

    fun setCodeWrap(v: Boolean) {
        prefs.codeWrap = v
        _state.update { it.copy(codeWrap = v) }
    }
}
