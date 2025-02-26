package com.salesforce.gorp;

import com.google.common.collect.Multimap;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;

public class FullExtractionTest extends TestBase
{
    public void testSimple() throws Exception
    {
        final String DEF =
"pattern %word ([a-zA-Z]+)\n"+
"template @base %word\n"+
"extract double {  \n"+
"  template @base value=$value(%word) value2=$value2(%word)\n"+
"}\n"+
"extract single {  \n"+
"  template value=$value(%word)\n"+
"}\n"+
                    "";
        DefinitionReader defR = DefinitionReader.reader(DEF);
        Gorp def = defR.read();

        ExtractionResult result = def.extract("value=foobar");
        assertNotNull(result);
        assertEquals("single", result.getId());
        Multimap<String,Object> stuff = result.asMap("id");
        assertEquals("single", stuff.get("id").iterator().next());
        assertEquals("foobar", stuff.get("value").iterator().next());
        assertEquals(2, stuff.size());

        result = def.extract("prefix value=a value2=b");
        assertNotNull(result);
        assertEquals("double", result.getId());
        stuff = result.asMap("id");
        assertEquals("double", stuff.get("id").iterator().next());
        assertEquals("a", stuff.get("value").iterator().next());
        assertEquals("b", stuff.get("value2").iterator().next());
        assertEquals(3, stuff.size());
    }

    public void testIntermediate() throws Exception
    {
        final String DEF =
"pattern %ws \\s+\n"+
"pattern %word [a-zA-Z]+\n"+
"pattern %phrase \\S+\n"+
"pattern %num \\d+\n"+
"pattern %ts %phrase\n"+
"pattern %ip %phrase\n"+
"extract interm {  \n"+
"  template <%num>$eventTimeStamp(%ts) $logAgent(%ip) RealSource: \"$logSrcIp(%ip)\"\n"+
"}\n"+
                    "";
        DefinitionReader defR = DefinitionReader.reader(DEF);
        Gorp def = defR.read();
        String INPUT = "<86>2015-05-12T20:57:53.302858+00:00 10.1.11.141 RealSource: \"10.10.5.3\"";

        ExtractionResult result = def.extract(INPUT);
        assertNotNull(result);
        assertEquals("interm", result.getId());
        Multimap<String,Object> values = result.asMap(null);
        assertEquals("10.10.5.3", values.get("logSrcIp").iterator().next());
    }
    
    public void testFull() throws Exception {
        final String DEF =
                "### First, let's define basic patterns using 'patterns' (regexps)\n" +
                        "# 'phrase' means non-space-sequence of characters; 'word' letters; 'num' digits\n" +
                        "pattern %word [a-zA-Z]+\n" +
                        "pattern %phrase \\S+\n" +
                        "pattern %num \\d+\n" +
                        "# more semantic macros, loosely defined\n" +
                        "pattern %ts %phrase\n" +
                        "pattern %ip %phrase\n" +
                        "pattern %maybeUUID %phrase\n" +
                        "pattern %hostname %phrase\n" +
                        "pattern %any .*\n" +
//"pattern %num1 [^<]*<%num\n" +
                        "pattern %num1 <%num>\n" +
                        "pattern %delim [^<]*" +
                        "\n" +
                        "# then possible 'templates', building blocks that consist of named patterns, literal text and possible embedded\n" +
                        "# 'anonymous' patterns (enclosed in %{....} and neither parsed (to substituted) nor escaped (like literal text))\n" +
                        "\n" +
                        "template @base <%num>$eventTimeStamp(%ts) $logAgent(%ip) RealSource: '$logSrcIp(%ip)'\\\n" +
                        " Environment: '$environment(%phrase)' UUID: '$uuid(%maybeUUID)'\\\n" +
                        " RawMsg: <%num>$rawMsgTS(%word %num %phrase) $logSrcHostname(%hostname)\\\n" +
                        " $appname(%word)[$appPID(%num)]\n" +
                        "\n" +
                        "# and then higher-level composition\n" +
                        "\n" +
                        "# sample:\n" +
                        "#<86>2015-03-16T20:57:53.302858+00:00 10.1.11.141 RealSource: '10.1.2.72' Environment: 'TEST' UUID: 'NO'\n" +
                        "# RawMsg: <86>Apr 16 20:54:53 host-prodnet sshd[12973]: Accepted keyboard-interactive/pam for badguy.ru from 1.2.3.4 port 58216 ssh2\n" +
                        "\n" +
                        "extract sshdMatch {\n" +
                        "  template @base: $authStatus(Accepted) $sshAuthMethod(%phrase) for $user(%hostname)\\\n" +
                        " from $srcIP(%ip) port $srcPort(%num) $sshProtocol(%phrase)\n" +
                        "  append 'service':'ssh', 'logType':'security', 'serviceType':'authentication' \n" +
                        "}\n" +
                        "extract baseMatch {\n" +
                        "  template @base\n" +
                        "}\n" +
                        "";

        // Minor simplification, used single-quotes for doubles above, change back
        DefinitionReader defR = DefinitionReader.reader(DEF.replace('\'', '"'));
        Gorp def = defR.read();

        // First things first, verify that @base matches
        String INPUT = "<86>2015-05-12T20:57:53.302858+00:00 10.1.11.141 RealSource:   '10.10.5.3'"
                + " Environment: 'TEST' UUID: 'NO'"
                + " RawMsg: <123>something 1324 more-or-less google.com sshd[137]";

        ExtractionResult result = def.extract(INPUT.replace('\'', '"'));
        assertNotNull(result);

        INPUT = "<86>2015-05-12T20:57:53.302858+00:00 10.1.11.141 RealSource:   '10.10.5.3'"
                + " Environment: 'TEST' UUID: 'NO'"
                + " RawMsg: <123>something 1324 more-or-less google.com sshd[137]"
                + ": Accepted keyboard-interactive/pam for badguy.ru from 1.2.3.4 port 58216 ssh2\r\n"
                + "<87>2015-05-12T20:57:53.302858+00:00 10.1.11.141 RealSource:   '10.10.5.4'"
                + " Environment: 'WORK' UUID: 'YES'"
                + " RawMsg: <123>something 1324 more-or-less google.com sshd[137]"
                + ": Accepted keyboard-interactive/pam for badguy.ru from 1.2.3.4 port 58216 ssh2"
        ;
        result = def.extractAllFound(INPUT.replace('\'', '"'));
        assertNotNull(result);
        assertEquals("sshdMatch", result.getId());
        Multimap<String, Object> values = result.asMap(null);
        assertEquals("badguy.ru", values.get("user").iterator().next());
        assertEquals("ssh2", values.get("sshProtocol").iterator().next());


        // Create a Clipboard object using getSystemClipboard() method
        Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
        INPUT = c.getData(DataFlavor.stringFlavor).toString();

        final String template_mark = "\n" + "[Template]";
        int last_pos = INPUT.lastIndexOf(template_mark);
        if (last_pos == -1) {
            assertNotNull(INPUT);
            return;
        }

        final String DEF2 = INPUT.substring(last_pos + template_mark.length());
        INPUT = INPUT.substring(0, last_pos);

        defR = DefinitionReader.reader(DEF2);
        def = defR.read();

        result = def.extractAllFound(INPUT);
        assertNotNull(result);
    }
}
