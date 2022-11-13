/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.cli;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.rules.TemporaryFolder;

import net.sourceforge.pmd.PMD;
import net.sourceforge.pmd.PMD.StatusCode;
import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.junit.JavaUtilLoggingRule;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.rule.MockRule;
import net.sourceforge.pmd.util.IOUtil;

/**
 *
 */
public class CoreCliTest {

    private static final String DUMMY_RULESET = "net/sourceforge/pmd/cli/FakeRuleset.xml";
    private static final String DUMMY_RULESET_WITH_VIOLATIONS = "net/sourceforge/pmd/cli/FakeRuleset2.xml";
    private static final String STRING_TO_REPLACE = "__should_be_replaced__";

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();
    @Rule
    public RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();
    @Rule
    public JavaUtilLoggingRule loggingRule = new JavaUtilLoggingRule(PMD.class.getPackage().getName()).mute();
    @Rule
    public final SystemOutRule outStreamCaptor = new SystemOutRule();
    @Rule
    public final SystemErrRule errStreamCaptor = new SystemErrRule();

    private Path srcDir;

    @Before
    public void setup() throws IOException {
        // set current directory to wd
        Path root = tempRoot();
        System.setProperty("user.dir", root.toString());

        // create a few files
        srcDir = Files.createDirectories(root.resolve("src"));
        writeString(srcDir.resolve("someSource.dummy"), "dummy text");
        // reset logger?
        Logger.getLogger("net.sourceforge.pmd");
    }


    @Test
    public void testPreExistingReportFile() throws IOException {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");
        // now we create the file
        Files.createDirectories(reportFile.getParent());
        writeString(reportFile, STRING_TO_REPLACE);

        assertTrue("Report file should exist", Files.exists(reportFile));

        runPmdSuccessfully("-no-cache", "-d", srcDir, "-R", DUMMY_RULESET, "-r", reportFile);

        assertNotEquals(readString(reportFile), STRING_TO_REPLACE);
    }

    @Test
    public void testPreExistingReportFileLongOption() throws IOException {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");
        // now we create the file
        Files.createDirectories(reportFile.getParent());
        writeString(reportFile, STRING_TO_REPLACE);

        assertTrue("Report file should exist", Files.exists(reportFile));

        runPmdSuccessfully("--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET, "--report-file", reportFile);

        assertNotEquals("Report file should have been overwritten", readString(reportFile), STRING_TO_REPLACE);
    }

    @Test
    public void testNonExistentReportFile() throws IOException {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");

        assertFalse("Report file should not exist", Files.exists(reportFile));

        try {
            runPmdSuccessfully("-no-cache", "-d", srcDir, "-R", DUMMY_RULESET, "-r", reportFile);
            assertTrue("Report file should have been created", Files.exists(reportFile));
        } finally {
            Files.deleteIfExists(reportFile);
        }
    }

    @Test
    public void testNonExistentReportFileLongOption() {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");

        assertFalse("Report file should not exist", Files.exists(reportFile));

        runPmdSuccessfully("--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET, "--report-file", reportFile);

        assertTrue("Report file should have been created", Files.exists(reportFile));
    }

    @Test
    public void testNoRelativizeWith() {
        startCapturingErrAndOut();
        runPmd(StatusCode.VIOLATIONS_FOUND, "--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET_WITH_VIOLATIONS);

        assertThat(outStreamCaptor.getLog(), containsString(srcDir.resolve("someSource.dummy").toString()));
    }

    @Test
    public void testRelativizeWith() {
        startCapturingErrAndOut();
        runPmd(StatusCode.VIOLATIONS_FOUND, "--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET_WITH_VIOLATIONS, "-z", srcDir.getParent());

        assertThat(outStreamCaptor.getLog(), not(containsString(srcDir.resolve("someSource.dummy").toString())));
        assertThat(outStreamCaptor.getLog(), containsString("src/someSource.dummy"));
    }

    @Test
    public void testRelativizeWithMultiple() {
        startCapturingErrAndOut();
        runPmd(StatusCode.VIOLATIONS_FOUND, "--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET_WITH_VIOLATIONS, "-z", srcDir.getParent(), srcDir);

        assertThat(outStreamCaptor.getLog(), not(containsString(srcDir.resolve("someSource.dummy").toString())));
        assertThat(outStreamCaptor.getLog(), containsString("someSource.dummy"));
    }

    @Test
    public void testRelativizeWithFileIsError() {
        startCapturingErrAndOut();
        runPmd(StatusCode.ERROR, "--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET_WITH_VIOLATIONS, "-z", srcDir.resolve("someSource.dummy"));

        assertThat(errStreamCaptor.getLog(), containsString(
            "Expected a directory path for option --relativize-paths-with, found a file: "
            + srcDir.resolve("someSource.dummy")));
    }

    @Test
    public void testFileCollectionWithUnknownFiles() throws IOException {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");
        Files.createFile(srcDir.resolve("foo.not_analysable"));
        assertFalse("Report file should not exist", Files.exists(reportFile));

        runPmdSuccessfully("--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET, "--report-file", reportFile, "--debug");

        assertTrue("Report file should have been created", Files.exists(reportFile));
        String reportText = IOUtil.readToString(Files.newBufferedReader(reportFile, StandardCharsets.UTF_8));
        assertThat(reportText, not(containsStringIgnoringCase("error")));
    }

    @Test
    public void testNonExistentReportFileDeprecatedOptions() {
        Path reportFile = tempRoot().resolve("out/reportFile.txt");

        assertFalse("Report file should not exist", Files.exists(reportFile));

        runPmdSuccessfully("-no-cache", "-dir", srcDir, "-rulesets", DUMMY_RULESET, "-reportfile", reportFile);

        assertTrue("Report file should have been created", Files.exists(reportFile));
        assertTrue(loggingRule.getLog().contains("Some deprecated options were used on the command-line, including -rulesets"));
        assertTrue(loggingRule.getLog().contains("Consider replacing it with --rulesets (or -R)"));
        // only one parameter is logged
        assertFalse(loggingRule.getLog().contains("Some deprecated options were used on the command-line, including -reportfile"));
        assertFalse(loggingRule.getLog().contains("Consider replacing it with --report-file"));
    }

    /**
     * This tests to create the report file in the current working directory.
     *
     * <p>Note: We can't change the cwd in the running VM, so the file will not be created
     * in the temporary folder, but really in the cwd. The test fails if a file already exists
     * and makes sure to cleanup the file afterwards.
     */
    @Test
    public void testRelativeReportFile() throws IOException {
        String reportFile = "reportFile.txt";
        Path absoluteReportFile = FileSystems.getDefault().getPath(reportFile).toAbsolutePath();
        // verify the file doesn't exist yet - we will delete the file at the end!
        assertFalse("Report file must not exist yet!", Files.exists(absoluteReportFile));

        try {
            runPmdSuccessfully("-no-cache", "-d", srcDir, "-R", DUMMY_RULESET, "-r", reportFile);
            assertTrue("Report file should have been created", Files.exists(absoluteReportFile));
        } finally {
            Files.deleteIfExists(absoluteReportFile);
        }
    }

    @Test
    public void testRelativeReportFileLongOption() throws IOException {
        String reportFile = "reportFile.txt";
        Path absoluteReportFile = FileSystems.getDefault().getPath(reportFile).toAbsolutePath();
        // verify the file doesn't exist yet - we will delete the file at the end!
        assertFalse("Report file must not exist yet!", Files.exists(absoluteReportFile));

        try {
            runPmdSuccessfully("--no-cache", "--dir", srcDir, "--rulesets", DUMMY_RULESET, "--report-file", reportFile);
            assertTrue("Report file should have been created", Files.exists(absoluteReportFile));
        } finally {
            Files.deleteIfExists(absoluteReportFile);
        }
    }

    @Test
    public void testReportToStdoutNotClosing() {
        PrintStream originalOut = System.out;
        PrintStream out = new PrintStream(new FilterOutputStream(originalOut) {
            @Override
            public void close() {
                fail("Stream must not be closed");
            }
        });
        try {
            System.setOut(out);
            startCapturingErrAndOut();
            runPmd(StatusCode.VIOLATIONS_FOUND, "--no-cache", "--dir", srcDir, "--rulesets", "dummy-basic");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testDeprecatedRulesetSyntaxOnCommandLine() {
        startCapturingErrAndOut();
        runPmd(StatusCode.VIOLATIONS_FOUND, "--no-cache", "--dir", srcDir, "--rulesets", "dummy-basic");
        MatcherAssert.assertThat(errStreamCaptor.getLog(), containsString("Ruleset reference 'dummy-basic' uses a deprecated form, use 'rulesets/dummy/basic.xml' instead"));
    }


    @Test
    public void testWrongCliOptionsDoNotPrintUsage() {
        String[] args = { "-invalid" };
        PmdParametersParseResult params = PmdParametersParseResult.extractParameters(args);
        assertTrue("Expected invalid args", params.isError());

        startCapturingErrAndOut();
        StatusCode code = PMD.runPmd(args);
        assertEquals(StatusCode.ERROR, code);
        assertThatErrAndOut(not(containsStringIgnoringCase("Available report formats and")));
    }

    private void assertThatErrAndOut(Matcher<String> matcher) {
        assertThat("stdout", outStreamCaptor.getLog(), matcher);
        assertThat("stderr", errStreamCaptor.getLog(), matcher);
    }

    private void startCapturingErrAndOut() {
        outStreamCaptor.enableLog();
        errStreamCaptor.enableLog();
        outStreamCaptor.mute();
        errStreamCaptor.mute();
    }

    // utilities



    private Path tempRoot() {
        return tempDir.getRoot().toPath();
    }


    private static void runPmdSuccessfully(Object... args) {
        runPmd(StatusCode.OK, args);
    }

    private static String[] argsToString(Object... args) {
        String[] result = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i].toString();
        }
        return result;
    }

    // available in Files on java 11+
    private static void writeString(Path path, String text) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(text);
        Files.write(path, encoded.array());
    }


    // available in Files on java 11+
    private static String readString(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return StandardCharsets.UTF_8.decode(buf).toString();
    }

    private static void runPmd(StatusCode expectedExitCode, Object... args) {
        StatusCode actualExitCode = PMD.runPmd(argsToString(args));
        assertEquals("Exit code", expectedExitCode, actualExitCode);
    }

    public static class FooRule extends MockRule {

        @Override
        public void apply(List<? extends Node> nodes, RuleContext ctx) {
            for (Node node : nodes) {
                ctx.addViolation(node);
            }
        }
    }


}
