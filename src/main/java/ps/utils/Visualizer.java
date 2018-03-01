package ps.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.UUID;

import com.google.common.io.Files;

import name.fraser.neil.plaintext.diff_match_patch;
import name.fraser.neil.plaintext.diff_match_patch.Diff;
import name.fraser.neil.plaintext.diff_match_patch.Operation;
import ps.models.ChangeTag;

/**
 * Visualization module
 */
public class Visualizer {
    private Visualizer() {
    }

    private final static diff_match_patch dmp = new diff_match_patch();

    /**
     * @param classification Obtained change classification.
     * @param original Original version of the document.
     * @param mod Modified version of the document.
     * Visualizes the change classification. Can be improved.
     */
    public static void visualize(ArrayList<ChangeTag> classification, String original, String mod) {
        String html = toHTML(classification, original, mod);

        try {
            String path = saveToFile(html);
            System.out.println("\nVisualization is found at: " + path);
        } catch (IOException e) {
            System.out.println("Cannot visualize classification.");
        }
    }

    private static String saveToFile(String html) throws IOException {
        File htmlTemplateFile = new File("src/main/resources/export/template.html");
        String htmlString = Files.toString(htmlTemplateFile, Charset.defaultCharset());
        String uniqueID = UUID.randomUUID().toString();
        String title = "change_class_" + uniqueID;
        String body = html;
        htmlString = htmlString.replace("$title", title);
        htmlString = htmlString.replace("$body", body);
        File newHtmlFile = new File(title + ".html");
        Files.write(htmlString, newHtmlFile, Charset.defaultCharset());
        return newHtmlFile.getAbsolutePath();
    }

    private static String toHTML(ArrayList<ChangeTag> classification, String original, String mod) {
        LinkedList<Diff> diffs = dmp.diff_main(original, mod);
        dmp.diff_cleanupSemantic(diffs);

        StringBuilder html = new StringBuilder();
        StringBuilder types = new StringBuilder();
        types.append("<ol>");
        int index = 0;

        for (int i = 0; i < diffs.size(); ++i) {
            Diff d = diffs.get(i);
            String text = d.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n",
                    "&para;<br>");
            switch (d.operation) {
            case INSERT:
                html.append("<ins style=\"background:#e6ffe6;\">").append(text).append("</ins>");
                html.append("<sup><a href=\"#" + (index + 1) + "\">" + (index + 1) + "</a></sup>");
                types.append(
                        "<li id=\"" + (index + 1) + "\">" + classification.get(index).getTag().toString() + "</li>");
                ++index;
                break;
            case DELETE:
                html.append("<del style=\"background:#ffe6e6;\">").append(text).append("</del>");
                if (i != diffs.size() - 1 && !diffs.get(i + 1).operation.equals(Operation.INSERT)) {
                    html.append("<sup><a href=\"#" + (index + 1) + "\">" + (index + 1) + "</a></sup>");
                    types.append("<li id=\"" + (index + 1) + "\">" + classification.get(index).getTag().toString()
                            + "</li>");
                    ++index;
                }
                break;
            case EQUAL:
                html.append("<span>").append(text).append("</span>");
                break;
            }
        }
        types.append("</ol>");
        return html.toString() + "&para;<br>" + types.toString();
    }

}