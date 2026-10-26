package dev.gomoku.novel;

import org.junit.Test;

/** Executes the upstream assertion suites with synthetic files and a local HTTP fixture. */
public class UpstreamReaderTest {
    @Test public void textEncodingChaptersPaginationAndClock() throws Exception {
        NovelBookTest.main(new String[0]);
    }
    @Test public void bookshelfDeduplicationAndProgress() throws Exception {
        NovelShelfTest.main(new String[0]);
    }
    @Test public void searchDownloadBoundsRedirectsAndCancellation() throws Exception {
        QishuxiaSourceTest.main(new String[0]);
    }
}
