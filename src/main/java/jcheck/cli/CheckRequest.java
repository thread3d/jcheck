package jcheck.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One check invocation, decoupled from argv so the daemon can serve it. */
public final class CheckRequest {

    public String root = ".";
    public String format = "human";
    /** 0 means "use the renderer's default for this format". */
    public int maxDiagnostics;
    /** -1 means "use jcheck.toml". */
    public int contextLines = -1;
    /** null means "use jcheck.toml". */
    public String profile;
    public List<String> deny = new ArrayList<>();
    public List<String> warn = new ArrayList<>();
    public List<String> allow = new ArrayList<>();
    public boolean denyWarnings;
    public boolean reportSuppressions;
    public List<Path> sources = new ArrayList<>();
    public List<Path> classpath = new ArrayList<>();
    public Integer release;

    public CheckRequest root(String v) {
        this.root = v;
        return this;
    }

    public CheckRequest format(String v) {
        this.format = v;
        return this;
    }

    public CheckRequest maxDiagnostics(int v) {
        this.maxDiagnostics = v;
        return this;
    }

    public CheckRequest contextLines(int v) {
        this.contextLines = v;
        return this;
    }

    public CheckRequest profile(String v) {
        this.profile = v;
        return this;
    }

    public CheckRequest deny(List<String> v) {
        this.deny = new ArrayList<>(v);
        return this;
    }

    public CheckRequest warn(List<String> v) {
        this.warn = new ArrayList<>(v);
        return this;
    }

    public CheckRequest allow(List<String> v) {
        this.allow = new ArrayList<>(v);
        return this;
    }

    public CheckRequest denyWarnings(boolean v) {
        this.denyWarnings = v;
        return this;
    }

    public CheckRequest reportSuppressions(boolean v) {
        this.reportSuppressions = v;
        return this;
    }

    public CheckRequest sources(List<Path> v) {
        this.sources = new ArrayList<>(v);
        return this;
    }

    public CheckRequest classpath(List<Path> v) {
        this.classpath = new ArrayList<>(v);
        return this;
    }

    public CheckRequest release(Integer v) {
        this.release = v;
        return this;
    }
}
