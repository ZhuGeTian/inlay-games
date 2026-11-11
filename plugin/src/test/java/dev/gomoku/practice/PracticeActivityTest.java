package dev.gomoku.practice;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class PracticeActivityTest {
    @Test public void preservesAnswerSnapshotAndOnlyDeletesAcknowledgedRecords() throws Exception {
        Path dir=Files.createTempDirectory("practice-activity-test");
        try {
            var outbox=new PracticeActivity(dir);
            var q=new PracticePaper.Question("q1","single","材料",List.of(),"填入____",List.of(),
                    List.of(new PracticePaper.Option("甲",null),new PracticePaper.Option("乙",null),new PracticePaper.Option("丙",null),new PracticePaper.Option("丁",null)),"B","解析","言语理解","用户提供");
            var paper=new PracticePaper("a".repeat(64),"测试卷",List.of(q));
            var event=PracticeActivity.snapshot(paper,0,"A");outbox.append(event);
            var redo=PracticeActivity.snapshot(paper,0,"B");outbox.append(redo);
            assertNotEquals(event.get("eventId"),redo.get("eventId"));
            var reopened=new PracticeActivity(dir);assertEquals(2,reopened.count());
            var batch=reopened.batch();assertEquals(2,batch.size());
            assertEquals("材料",event.get("material").getAsString());
            assertEquals("A",event.get("selected").getAsString());
            assertEquals("B",event.get("answer").getAsString());
            var invalid=new JsonArray();invalid.add("../../not-our-file");
            try { reopened.acknowledge(batch,invalid);fail("Invalid acknowledgment accepted"); } catch(java.io.IOException expected) {}
            assertEquals(2,reopened.count());
            var accepted=new JsonArray();accepted.add(event.get("eventId"));reopened.acknowledge(batch,accepted);
            assertEquals(1,reopened.count());assertEquals(redo,reopened.batch().get(0));
            reopened.acknowledge(batch,accepted);assertEquals(1,reopened.count());
        } finally { try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);} Files.delete(dir); }
    }
    @Test public void invalidChoiceCannotBecomeAnEvent() {
        var q=new PracticePaper.Question("q1","boolean","",List.of(),"题目",List.of(),List.of(new PracticePaper.Option("对",null),new PracticePaper.Option("错",null)),"","","","");
        var paper=new PracticePaper("a".repeat(64),"测试",List.of(q));
        assertThrows(IllegalArgumentException.class,()->PracticeActivity.snapshot(paper,0,"C"));
        assertEquals("",PracticeActivity.snapshot(paper,0,"A").get("answer").getAsString());
    }
}
