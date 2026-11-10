package app.jingye.reader

import android.app.Application
import app.jingye.reader.backend.FanqieService
import app.jingye.reader.data.BookSource
import app.jingye.reader.data.LibraryStore
import app.jingye.reader.data.LocalTxtSource
import app.jingye.reader.data.ReaderPrefs

class JingyeApp : Application() {
    lateinit var library: LibraryStore
    lateinit var localSource: LocalTxtSource
    lateinit var source: BookSource
    lateinit var prefs: ReaderPrefs
    lateinit var fanqie: FanqieService

    override fun onCreate() {
        super.onCreate()
        prefs = ReaderPrefs(this)
        library = LibraryStore(this)
        localSource = LocalTxtSource(library)
        source = localSource
        fanqie = FanqieService(this, prefs, library, localSource)
    }
}
