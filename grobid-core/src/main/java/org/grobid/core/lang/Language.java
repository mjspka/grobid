package org.grobid.core.lang;

import org.grobid.core.exceptions.GrobidException;

/**
 * Date: 11/24/11
 * Time: 11:39 AM
 *
 * @author Vyacheslav Zholudev
 */
public final class Language {
    //common language constants
    public static final String EN = "en";
    public static final String DE = "de";
    public static final String FR = "fr";

    private String langId;
    private double confidence;

	// default construction for jackson mapping
	public Language() {}

    public Language(String langId, double confidence) {
        if (langId == null) {
            throw new GrobidException("Language id cannot be null");
        }

        if ((langId.length() != 3 && langId.length() != 2 && (!langId.equals("sorb")) && (!langId.equals("zh-cn"))) || !(Character.isLetter(langId.charAt(0)) && Character.isLetter(langId.charAt(1)))) {
            throw new GrobidException("Language id should consist of two or three letters, but was: " + langId);
        }

        this.langId = langId;
        this.confidence = confidence;
    }

    public String getLangId() {
        return langId;
    }
	
    public void setLang(String lang) {
        this.langId = langId;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public double getConfidence() {
        return confidence;
    }

	public void setConf(double conf) {
		this.confidence = confidence;
	}

    @Override
    public String toString() {
        return langId + ";" + confidence;
    }

    public String toJSON() {
        return "{\"lang\":\""+langId+"\", \"conf\": "+confidence+"}";
    }
}
