package ps.changeclassifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.analysis.en.EnglishMinimalStemmer;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;

import edu.cmu.lti.jawjaw.JAWJAW;
import edu.cmu.lti.jawjaw.pobj.POS;
import edu.cmu.lti.lexical_db.ILexicalDatabase;
import edu.cmu.lti.lexical_db.NictWordNet;
import edu.cmu.lti.ws4j.Relatedness;
import edu.cmu.lti.ws4j.impl.HirstStOnge;
import edu.cmu.lti.ws4j.impl.JiangConrath;
import edu.cmu.lti.ws4j.impl.Lesk;
import edu.cmu.lti.ws4j.util.PorterStemmer;
import edu.cmu.lti.ws4j.util.WS4JConfiguration;
import name.fraser.neil.plaintext.diff_match_patch;
import name.fraser.neil.plaintext.diff_match_patch.Diff;
import ps.models.Change;
import ps.utils.FastTag;
import ps.utils.LP;

/**
 * Class that provides tools for determining change's meaning.
 * Uses google-diff-match-patch library (@see https://github.com/GerHobbelt/google-diff-match-patch for more information)
 */
public class ChangeAnalyzer {

    // WordNet database
    private static ILexicalDatabase db = new NictWordNet();
    // Diff-Match-Patch tool
    private static diff_match_patch dmp = new diff_match_patch();

    private ChangeAnalyzer() {
    }

    /*
    Checks if a change happened in a citation.
    Simple matching.
    */
    protected static boolean isCitation(Change change) {
        if (LP.isQuote(change.getBefore()) && LP.isQuote(change.getAfter())) {
            // Double checking, but should never happen
            if (!change.getBefore().equals(change.getAfter())) {
                return true;
            }
        }
        return false;
    }

    /*
    Checks if change was a simple case of formatting.
    Simple matching.
    */
    protected static boolean isFormatting(Change change, String text1, String text2) {
        if (LP.isFormattingSymbol(change.getBefore()) && LP.isFormattingSymbol(change.getAfter())) {
            return true;
        }
        if (LP.isFormattingSymbol(change.getAfter()) && change.getBefore().equals("")) {
            char c1 = text1.charAt(change.getPos1() - 1);
            char c2 = text1.charAt(change.getPos1() + change.getBefore().length());
            if (!Character.isLetter(c1) || !Character.isLetter(c2)) {
                return true;
            }
        }
        if (LP.isFormattingSymbol(change.getBefore()) && change.getAfter().equals("")) {
            char c1 = text2.charAt(change.getPos2() - 1);
            char c2 = text2.charAt(change.getPos2() + change.getAfter().length());
            if (!Character.isLetter(c1) || !Character.isLetter(c2)) {
                return true;
            }
        }
        return false;
    }

    /*
    1. Check if both changes are words.
    2. If both are not in dictionary return UNDEFINED.
    3. If the first one is not in dictionary and has a distance of <= 2, then SPELLING.
    4. If both words are correct, and are equal ignoring case, then it is spelling (e.g. north -> North).
    5. Otherwise, keep going.
    */
    protected static int isSpelling(Change change) {
        ArrayList<String> w1 = LP.tokenizeStopStem(change.getBefore(), false, false);
        ArrayList<String> w2 = LP.tokenizeStopStem(change.getAfter(), false, false);
        String before = "";
        String after = "";
        if (w1.size() == 1 && w2.size() == 1) {
            before = w1.get(0);
            after = w2.get(0);

            boolean misspelling = !LP.inDictionary(before);
            boolean correct = LP.inDictionary(after);
            if (misspelling && !correct) {
                return -1;
            }
            if (misspelling && correct) {
                LinkedList<Diff> diff = dmp.diff_main(before, after);
                if (dmp.diff_levenshtein(diff) <= 2) {
                    return 1;
                }
            }
            if (!misspelling && correct) {
                if (before.equalsIgnoreCase(after)) {
                    return 1;
                }
            }
        }
        return 0;
    }

    /*
    1. Checks if both changes are individual words.
    2. Both words must be in dictionary.
    3. Determine part-of-speech for both words.
    4. Both words must have the same part of speech.
    5. Modify tags to make them match to WS4J POS-class.
    6. Simple stemming (e.g. remove -s from plurals etc).
    6. Check if words are synonyms (they are if one of them in the synset of the other).
    7. Calculate similarity score (HirstStOnge allows to calculate the score between numerous POS).
    8. Threshold of 5 (min: 0, max: 16).
    */
    protected static int substitutionSimilarity(Change change) {
        ArrayList<String> w1 = LP.tokenizeStopStem(change.getBefore(), false, false);
        ArrayList<String> w2 = LP.tokenizeStopStem(change.getAfter(), false, false);
        String before, after;
        String tag1, tag2;
        POS pos1 = null, pos2 = null;

        if (w1.size() == 1 && w2.size() == 1) {
            before = w1.get(0);
            after = w2.get(0);

            if (!LP.inDictionary(before) || !LP.inDictionary(after)) {
                return -1;
            }

            tag1 = FastTag.tag(w1).get(0);
            tag2 = FastTag.tag(w2).get(0);

            boolean cond1 = tag1.substring(0, 2).equals(tag2.substring(0, 2));
            boolean cond2 = ((tag1.equals("MD") && tag2.startsWith("VB"))
                    || (tag1.startsWith("VB") && tag2.equals("MD")));
            boolean cond3 = ((tag1.equals("CD") && tag2.startsWith("NN"))
                    || (tag1.startsWith("NN") && tag2.equals("CD")));

            if (!cond1 && !cond2 && !cond3) {
                return -2;
            }

            ArrayList<String> tmp1 = LP.stem(before, tag1);
            ArrayList<String> tmp2 = LP.stem(after, tag2);

            before = tmp1.get(0);
            pos1 = POS.valueOf(tmp1.get(1));
            after = tmp2.get(0);
            pos2 = POS.valueOf(tmp2.get(1));

            if (pos1 == null || pos2 == null) {
                return -1;
            }

            Set<String> s = JAWJAW.findSynonyms(before, pos1);

            for (String str : s) {
                if (str.equals(after)) {
                    return 2;
                }
            }

            before = before + "#" + pos1;
            after = after + "#" + pos2;
            WS4JConfiguration.getInstance().setMFS(false);
            double sim = new HirstStOnge(db).calcRelatednessOfWords(before, after);
            if (sim >= 5) {
                return 1;
            } else {
                return 0;
            }
        }

        return -2;
    }

    /*
    Checks if change was rephrasing.
    Uses Fernando and Stevenson semantic similarity measure.
    */
    protected static boolean isRephrasing(Change changed_chars, Change changed_word, Change changed_sent) {
        ArrayList<Change> localChanges = ChangeDetector.getChanges(changed_sent.getBefore(), changed_sent.getAfter());
        // things that can't be rephrasing
        boolean cond1, cond2, cond3, cond4, cond5;
        boolean other = true;
        for (Change c : localChanges) {
            cond1 = isCitation(c);
            cond2 = isFormatting(c, changed_sent.getBefore(), changed_sent.getAfter());
            cond3 = isSpelling(c) != 0;
            cond4 = LP.isNumber(c.getBefore()) || LP.isSymbol(c.getBefore());
            cond5 = LP.isNumber(c.getAfter()) || LP.isSymbol(c.getAfter());
            other = cond1 || cond2 || cond3 || (cond4 && cond5);
        }
        if (other) {
            return false;
        }

        int sentCount1 = LP.numberOfSentences(changed_sent.getBefore());
        int sentCount2 = LP.numberOfSentences(changed_sent.getAfter());

        if ((sentCount1 != 1 && sentCount1 != 2) || (sentCount2 != 1 && sentCount2 != 2)) {
            return false;
        }

        ArrayList<String> words1 = LP.tokenizeStopStem(changed_sent.getBefore(), true, false);
        ArrayList<String> words2 = LP.tokenizeStopStem(changed_sent.getAfter(), true, false);

        if (words1.isEmpty() || words2.isEmpty()) {
            return false;
        }

        // get POS-tags for all words
        List<String> tags1 = FastTag.tag(words1);
        List<String> tags2 = FastTag.tag(words2);

        // assign each word its tag for better similarity scores
        for (int i = 0; i < words1.size(); ++i) {
            ArrayList<String> wp = LP.stem(words1.get(i), tags1.get(i));
            words1.set(i, wp.get(0) + "#" + wp.get(1));
        }

        for (int i = 0; i < words2.size(); ++i) {
            ArrayList<String> wp = LP.stem(words2.get(i), tags2.get(i));
            words2.set(i, wp.get(0) + "#" + wp.get(1));
        }

        Set<String> wd1 = new TreeSet<String>(words1);
        Set<String> wd2 = new TreeSet<String>(words2);
        Set<String> w = new TreeSet<>(wd1);
        w.addAll(wd2);

        // create a sorted vector of words
        ArrayList<String> words = new ArrayList<>(w);
        words.sort((o1, o2) -> o1.compareTo(o2));

        // represent first sentence as a vector
        double[] a = words.stream().mapToDouble(word -> {
            if (wd1.contains(word)) {
                return 1.0;
            } else {
                return 0.0;
            }
        }).toArray();

        // represent second sentence as a vector
        double[] b = words.stream().mapToDouble(word -> {
            if (wd2.contains(word)) {
                return 1.0;
            } else {
                return 0.0;
            }
        }).toArray();

        // calculte similarity matrix
        WS4JConfiguration.getInstance().setMFS(false);
        double[][] W = new JiangConrath(db).getNormalizedSimilarityMatrix(w.toArray(new String[w.size()]),
                w.toArray(new String[w.size()]));

        // calculate similarity score
        double sim = LP.fernandoSim(a, b, W);
        // think about threshold
        return sim >= 0.3;
    }

    /*
    Checks if a change was grammar correction.
    Rule-based approach by Daniel Naber.
    */
    protected static boolean isGrammar(Change change) {
        boolean correct_before = false, correct_after = false;
        JLanguageTool langTool = new JLanguageTool(new AmericanEnglish());
        List<RuleMatch> matches = null;
        try {
            matches = langTool.check(change.getBefore());
            matches.removeIf(rule -> rule.getRule() instanceof SpellingCheckRule);
            correct_before = matches.isEmpty();

            matches = langTool.check(change.getAfter());
            matches.removeIf(rule -> rule.getRule() instanceof SpellingCheckRule);
            correct_after = matches.isEmpty();
        } catch (IOException e) {
            return false;
        }
        return !correct_before && correct_after;
    }

    /*
    Checks how a change influenced the topic of a text.
    Novel feature-based approach inspired by topic modelling and feature-based word similarity measures.
    */
    protected static int relatedTopics(Change change, String text) {
        String before = "", after = "";
        if (change.getBefore().length() < change.getAfter().length()) {
            before = text;
            after = change.getAfter();
        } else if (change.getAfter().equals("")) {
            before = text;
            after = text.substring(change.getPos1())
                    + text.substring(change.getPos1() + change.getBefore().length(), text.length());
        } else {
            before = text;
            after = text.substring(change.getPos1()) + change.getAfter()
                    + text.substring(change.getPos1() + change.getBefore().length(), text.length());
        }
        ArrayList<String> features = LP.getFeatures(before, after);
        if (features.isEmpty()) {
            return -1;
        }

        Map<String, Double> dist1 = LP.getDistribution(features, before);
        Map<String, Double> dist2 = LP.getDistribution(features, after);

        boolean emptymap = true;
        for (double d1 : dist1.values()) {
            if (d1 != 0.0) {
                emptymap = false;
                break;
            }
        }
        if (emptymap) {
            return -1;
        }

        emptymap = true;
        for (double d2 : dist2.values()) {
            if (d2 != 0.0) {
                emptymap = false;
                break;
            }
        }
        if (emptymap) {
            return -1;
        }

        double score = LP.jsd(dist1, dist2);
        return score > 0.5 ? 0 : 1;
    }

}