package io.github.radeklesniewski.dq.json.catalog;

import io.github.radeklesniewski.dq.rule.Rule;
import io.github.radeklesniewski.dq.rule.RuleCatalog;
import io.github.radeklesniewski.dq.rule.RuleLogicRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * A catalog file that is re-read when its modification time changes; see {@link JsonRuleCatalogs#watching}. Only the
 * caching and change detection live here; loading is {@link JsonRuleCatalogs#load(Path, RuleLogicRegistry)}'s job.
 * Thread-safe.
 */
final class WatchingCatalog implements RuleCatalog {

    private final Path file;
    private final RuleLogicRegistry logic;
    private FileTime loadedVersion;
    private List<Rule> rules;

    /** Loads the file once, so an invalid catalog fails now rather than at the first run. */
    WatchingCatalog(Path file, RuleLogicRegistry logic) {
        this.file = file;
        this.logic = logic;
        rules();
    }

    @Override
    public synchronized List<Rule> rules() {
        FileTime version = modificationTime();
        if (!version.equals(loadedVersion)) {
            rules = JsonRuleCatalogs.load(file, logic).rules();
            loadedVersion = version; // only after a successful load, so a broken edit is retried next time
        }
        return rules;
    }

    private FileTime modificationTime() {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw JsonRuleCatalogs.cantRead(file, e);
        }
    }

    @Override
    public String toString() {
        return "JsonRuleCatalogs.watching(" + file + ")";
    }
}
