package org.grobid.trainer.evaluation;

import com.google.common.base.Function;
import org.chasen.crfpp.Tagger;
import org.grobid.core.engines.tagging.GenericTagger;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.engines.tagging.GrobidCRFEngine;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.grobid.trainer.LabelStat;
import org.grobid.trainer.Stats;

import org.apache.commons.io.FileUtils;

/**
 * Evaluation of the parsing of citation.
 * 
 * @author Patrice Lopez
 */


public class EvaluationUtilities {
	/**
	 * Method for running a CRF tagger for evaluation purpose (i.e. with
	 * expected and actual labels).
	 * 
	 * @param ress
	 *            list
	 * @param tagger
	 *            a tagger
	 * @return a report
	 */
	public static String taggerRun(List<String> ress, Tagger tagger) {
		// clear internal context
		tagger.clear();
		StringBuilder res = new StringBuilder();

		// we have to re-inject the pre-tags because they are removed by the JNI
		// parse method
		ArrayList<String> pretags = new ArrayList<String>();
		// add context
		for (String piece : ress) {
			if (piece.trim().length() == 0) {
				// parse and change internal stated as 'parsed'
				if (!tagger.parse()) {
					// throw an exception
					throw new RuntimeException("CRF++ parsing failed.");
				}

				for (int i = 0; i < tagger.size(); i++) {
					for (int j = 0; j < tagger.xsize(); j++) {
						res.append(tagger.x(i, j)).append("\t");
					}
					res.append(pretags.get(i)).append("\t");
					res.append(tagger.y2(i));
					res.append("\n");
				}
				res.append(" \n");
				// clear internal context
				tagger.clear();
				pretags = new ArrayList<String>();
			} else {
				tagger.add(piece);
				tagger.add("\n");
				// get last tag
				StringTokenizer tokenizer = new StringTokenizer(piece, " \t");
				while (tokenizer.hasMoreTokens()) {
					String toke = tokenizer.nextToken();
					if (!tokenizer.hasMoreTokens()) {
						pretags.add(toke);
					}
				}
			}
		}

		// parse and change internal stated as 'parsed'
		if (!tagger.parse()) {
			// throw an exception
			throw new RuntimeException("CRF++ parsing failed.");
		}

		for (int i = 0; i < tagger.size(); i++) {
			for (int j = 0; j < tagger.xsize(); j++) {
				res.append(tagger.x(i, j)).append("\t");
			}
			res.append(pretags.get(i)).append("\t");
			res.append(tagger.y2(i));
			res.append("\n");
		}
		res.append("\n");

		return res.toString();
	}

    public static String evaluateStandard(String path, final GenericTagger tagger) {
        return evaluateStandard(path, new Function<List<String>, String>() {
            @Override
            public String apply(List<String> strings) {
                return tagger.label(strings);
            }
        });
    }

	public static String evaluateStandard(String path, Function<List<String>, String> taggerFunction) {
		StringBuilder report = new StringBuilder();
		Stats wordStats = new Stats();
		Stats fieldStats = new Stats();

		try {
			final BufferedReader bufReader = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));

			String theResult = null;
			String line = null;
			long time = 0;
			List<String> citationBlocks = new ArrayList<String>();
			List<String> expected = new ArrayList<String>();
			// it's quite bad to have something CRF engine dependent here, but hard to avoid
			// the expected vector is used only with CRF++
			if (GrobidProperties.getGrobidCRFEngine() == GrobidCRFEngine.CRFPP) {
				StringBuffer buffer = new StringBuffer();
				time = System.currentTimeMillis();
				while ((line = bufReader.readLine()) != null) {
					if (line.trim().length() == 0) {
						buffer.append(taggerFunction.apply(citationBlocks));
						buffer.append("\n");
						citationBlocks = new ArrayList<String>();
					}
					else {
						citationBlocks.add(line);
						int ind = line.lastIndexOf(" ");
						if (ind == -1) 
							ind = line.lastIndexOf("\t");
						if (ind != -1)
							expected.add(line.substring(ind+1, line.length()));
					}
				}
				theResult = buffer.toString();
			}
			else {
				while ((line = bufReader.readLine()) != null) {
					citationBlocks.add(line);
				}
				time = System.currentTimeMillis();
				theResult = taggerFunction.apply(citationBlocks);
			}
			bufReader.close();

            System.out.println("Labeling took: " + (System.currentTimeMillis() - time) + " ms");
			//FileUtils.writeStringToFile(new File("/tmp/x.txt"), theResult);
			StringTokenizer stt = new StringTokenizer(theResult, "\n");
			int e = 0;
			while (stt.hasMoreTokens()) {
				line = stt.nextToken();

				if (line.trim().length() == 0) {
					continue;
				}
				// the two last tokens, separated by a tabulation, gives the
				// expected label and, last, the resulting label -> for Wapiti
				// for CRF++, we get the expected label from the dedicated vector
				StringTokenizer st = new StringTokenizer(line, "\t ");
				String obtainedToken = null;
				String expectedToken = null;
				while (st.hasMoreTokens()) {
					obtainedToken = st.nextToken();
					if (obtainedToken != null) {
						if (obtainedToken.startsWith("I-") || obtainedToken.startsWith("E-") || obtainedToken.startsWith("B-")) {
							obtainedToken = obtainedToken.substring(2, obtainedToken.length());
						}
					}
					if (st.hasMoreTokens()) {
						expectedToken = obtainedToken;
					}
				}

				// it's quite bad to have something CRF engine dependent here, but hard to avoid
				if (GrobidProperties.getGrobidCRFEngine() == GrobidCRFEngine.CRFPP) {
					expectedToken = expected.get(e);
					if (expectedToken.startsWith("I-") || expectedToken.startsWith("E-") || expectedToken.startsWith("B-")) {
						expectedToken = expectedToken.substring(2, expectedToken.length());
					}
					e++;
				}
				
				 //System.out.println(expectedToken + " / " + obtainedToken);

				if ((expectedToken == null) || (obtainedToken == null)) {
					continue;
				}

				processCounters(wordStats, obtainedToken, expectedToken);
			}

			bufReader.close();
			// print report
			int i = 0;

			// word
			report.append("\n===== Token-level results =====\n\n");
			report.append(computeMetrics(wordStats));

			/*int cumulated_tp = 0;
			int cumulated_fp = 0;
			int cumulated_tn = 0;
			int cumulated_fn = 0;
			// int cumulated_all = 0;
			double cumulated_f0 = 0.0;
			double cumulated_accuracy = 0.0;
			double cumulated_precision = 0.0;
			double cumulated_recall = 0.0;
			int totalValidFields = 0; // in case a class should not be
										// considered in the training set
			// because there is no expected class instance
			int totalTokens = 0;

			while (i < labels.size()) {
				totalTokens += counterExpected.get(i);
				i++;
			}

			System.out.println("total: " + totalTokens);

			// report for absolute values for each token type
			i = 0;
			while (i < labels.size()) {
				String label = labels.get(i);
				report.append(label);
				if (label.length() < 12)
					report.append("\t");

				int tp = counterObserved.get(i); // true positives
				int fp = counterFalsePositive.get(i); // false positves
				int fn = counterFalseNegative.get(i); // false negative
				int tn = totalTokens - tp - (fp + fn); // true negatives
				int all = counterExpected.get(i); // all expected

				report.append(label).append(" (token): " + "true positives: ").append(tp).append("\n\t\tfalse positives:").append(fp)
						.append("\n\t\ttrue negatives: ").append(tn).append("\n\t\tfalse negatives: ").append(fn).append("\n");
				report.append("\t\tall expected: ").append(all).append("\n\n");
				i++;
			}

			report.append("label\t\taccuracy\tprecision\trecall\t\tf1\n\n");

			// report for precision, recall, etc.
			i = 0;
			while (i < labels.size()) {
				String label = labels.get(i);
				report.append(label);
				if (label.length() < 12)
					report.append("\t");

				int tp = counterObserved.get(i); // true positives
				int fp = counterFalsePositive.get(i); // false positves
				int fn = counterFalseNegative.get(i); // false negative
				int tn = totalTokens - tp - (fp + fn); // true negatives
				int all = counterExpected.get(i); // all expected

				if (all != 0) {
					totalValidFields++;
				}

				double accuracy = (double) (tp + tn) / (tp + fp + tn + fn);
				report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

				double precision = 0.0;
				if (tp + fp != 0.0) {
					precision = (double) (tp) / (tp + fp);
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

				double recall = 0.0;
				if (tp + fn != 0) {
					recall = (double) (tp) / (tp + fn);
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

				double f0 = 0.0;
				if (precision + recall != 0) {
					f0 = (2 * precision * recall) / (precision + recall);
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));

				report.append("\n");

				cumulated_tp += tp;
				cumulated_fp += fp;
				cumulated_tn += tn;
				cumulated_fn += fn;
				if (all != 0) {
					// cumulated_all += all;
					cumulated_f0 += f0;
					cumulated_accuracy += accuracy;
					cumulated_precision += precision;
					cumulated_recall += recall;
				}

				i++;
			}
			report.append("\n");
			report.append("all labels\t");

			// micro average measure
			double accuracy = (double) (cumulated_tp + cumulated_tn) / (cumulated_tp + cumulated_fp + cumulated_tn + cumulated_fn);
			if (accuracy > 1)
				accuracy = 1.0;
			report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

			double precision = (double) cumulated_tp / (cumulated_tp + cumulated_fp);
			if (precision > 1)
				precision = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

			double recall = (double) cumulated_tp / (cumulated_tp + cumulated_fn);
			if (recall > 1)
				recall = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

			double f0 = (2 * precision * recall) / (precision + recall);
			// double f0 = cumulated_f0 / labels.size();
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));
			report.append("\t(micro average)");
			report.append("\n");

			// macro average measure
			report.append("\t\t");
			accuracy = cumulated_accuracy / (totalValidFields);
			if (accuracy > 1)
				accuracy = 1.0;
			report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

			precision = totalValidFields / cumulated_precision;
			if (precision > 1)
				precision = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

			recall = cumulated_recall / totalValidFields;
			if (recall > 1)
				recall = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

			f0 = cumulated_f0 / totalValidFields;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));

			report.append("\t(macro average)");
			report.append("\n");
			*/
			// field: a field is simply a sequence of word...
			// we do a second pass...
			boolean allGood = true;
			String lastPreviousToken = null;
			String lastCurrentToken = null;
			stt = new StringTokenizer(theResult, "\n");
			e = 0;
			while (stt.hasMoreTokens()) {
				line = stt.nextToken();
				if ((line.trim().length() == 0) && (lastPreviousToken != null) && (lastCurrentToken != null)) {
					// end of last field
					LabelStat previousLabelStat = fieldStats.getLabelStat(lastPreviousToken);

					if (allGood) {
						previousLabelStat.incrementObserved();
					} else {
						previousLabelStat.incrementFalseNegative();
					}

					previousLabelStat.incrementExpected();

					LabelStat currentLabelStat = fieldStats.getLabelStat(lastCurrentToken);
					if (!allGood) {
						currentLabelStat.incrementFalsePositive();
						// erroneous observed field (false positive)
					}
					allGood = true;

					lastPreviousToken = null;
					lastCurrentToken = null;

					continue;
				}
				// the two last tokens, separated by a tabulation, gives the
				// expected label and, last,
				// the resulting label
				StringTokenizer st = new StringTokenizer(line, "\t ");
				String currentToken = null;
				String previousToken = null;
				while (st.hasMoreTokens()) {
					currentToken = st.nextToken();
					if (currentToken != null) {
						if (currentToken.startsWith("I-") || currentToken.startsWith("E-") || currentToken.startsWith("B-")) {
							currentToken = currentToken.substring(2, currentToken.length());
						}
					}
					if (st.hasMoreTokens()) {
						previousToken = currentToken;
					}
				}

				// it's quite bad to have something CRF engine dependent here, but hard to avoid
				if (GrobidProperties.getGrobidCRFEngine() == GrobidCRFEngine.CRFPP) {
					previousToken = expected.get(e);
					if (previousToken.startsWith("I-") || previousToken.startsWith("E-") || previousToken.startsWith("B-")) {
						previousToken = previousToken.substring(2, previousToken.length());
					}
					e++;
				}

				if ((previousToken == null) || (currentToken == null)) {
					lastPreviousToken = null;
					lastCurrentToken = null;
					continue;
				}
				if ((lastPreviousToken != null) && (!previousToken.equals(lastPreviousToken))) {
					// new field
					LabelStat previousLabelStat = fieldStats.getLabelStat(lastPreviousToken);
					if (allGood) {
						previousLabelStat.incrementObserved();
					} else {
						previousLabelStat.incrementFalseNegative();
					}

					previousLabelStat.incrementExpected();
				}

				if ((lastCurrentToken != null) && (!currentToken.equals(lastCurrentToken))) {
					// new field
					LabelStat currentLabelStat = fieldStats.getLabelStat(lastCurrentToken);
					if (!allGood) {
						currentLabelStat.incrementFalsePositive();
						// erroneous observed field (false positive)
					}
				}

				if (((lastPreviousToken != null) && (!previousToken.equals(lastPreviousToken)))
						|| ((lastCurrentToken != null) && (!currentToken.equals(lastCurrentToken)))) {
					allGood = true;
				}

				if (!currentToken.equals(previousToken)) {
					allGood = false;
				}

				lastPreviousToken = previousToken;
				lastCurrentToken = currentToken;
			}
			
			// and finally this is for the last field which is closing with the end of the sequence labelling
			// this is new from 26.03.2014
			if ((lastPreviousToken != null) && (lastCurrentToken != null)) {
				// end of last field
				LabelStat previousLabelStat = fieldStats.getLabelStat(lastPreviousToken);

				if (allGood) {
					previousLabelStat.incrementObserved();
				} else {
					previousLabelStat.incrementFalseNegative();
				}

				previousLabelStat.incrementExpected();

				LabelStat currentLabelStat = fieldStats.getLabelStat(lastCurrentToken);
				if (!allGood) {
					currentLabelStat.incrementFalsePositive();
					// erroneous observed field (false positive)
				}
			} 

			report.append("\n===== Field-level results =====\n");
			report.append(computeMetrics(fieldStats));
			
			/*report.append("\nlabel\t\taccuracy\tprecision\trecall\t\tf1\n\n");

			cumulated_tp = 0;
			cumulated_fp = 0;
			cumulated_tn = 0;
			cumulated_fn = 0;
			cumulated_f0 = 0.0;
			// cumulated_all = 0;
			cumulated_accuracy = 0.0;
			cumulated_precision = 0.0;
			cumulated_recall = 0.0;
			totalValidFields = 0;

			int totalFields = 0;
			i = 0;
			while (i < labels2.size()) {
				totalFields += counterExpected2.get(i);
				i++;
			}

			i = 0;
			while (i < labels2.size()) {
				totalFields += counterFalsePositive2.get(i);
				i++;
			}

			i = 0;
			while (i < labels2.size()) {
				String label = labels2.get(i);
				if (label.equals("<other>")) {
					i++;
					continue;
				}

				report.append(label);

				if (label.length() < 12) {
					report.append("\t");
				}
				int tp = counterObserved2.get(i); // true positives
				int fp = counterFalsePositive2.get(i); // false positives
				int fn = counterFalseNegative2.get(i); // false negative
				int tn = totalFields - tp - (fp + fn); // true negatives
				int all = counterExpected2.get(i); // all expected

				if (all != 0) {
					totalValidFields++;
				}

				accuracy = (double) (tp + tn) / (tp + fp + tn + fn);
				report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

				// report.append("\t"+ "-");

				// precision = (double) (tp) / (tp + fp);
				if ((tp + fp) == 0) {
					precision = 0.0;
				} else {
					precision = (double) (tp) / (tp + fp);
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

				// recall = 0.0;
				if ((tp == 0) || (all == 0)) {
					recall = 0.0;
				} else {
					recall = (double) (tp) / all;
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

				// f0 = 0.0;
				if (precision + recall == 0) {
					f0 = 0.0;
				} else {
					f0 = (2 * precision * recall) / (precision + recall);
				}
				report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));

				report.append("\n");

				cumulated_tp += tp;
				cumulated_fp += fp;
				cumulated_tn += tn;
				cumulated_fn += fn;
				if (all != 0) {
					// cumulated_all += all;
					cumulated_f0 += f0;
					cumulated_accuracy += accuracy;
					cumulated_precision += precision;
					cumulated_recall += recall;
				}
				i++;
			}

			report.append("\n");
			report.append("all fields\t");

			// micro average over measures
			accuracy = (double) (cumulated_tp + cumulated_tn) / (cumulated_tp + cumulated_fp + cumulated_tn + cumulated_fn);
			if (accuracy > 1)
				accuracy = 1.0;
			report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

			precision = (double) cumulated_tp / (cumulated_tp + cumulated_fp);
			if (precision > 1)
				precision = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

			recall = (double) cumulated_tp / (cumulated_tp + cumulated_fn);
			if (recall > 1)
				recall = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

			f0 = (2 * precision * recall) / (precision + recall);
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));
			report.append("\t(micro average)");
			report.append("\n");

			// macro average over measures
			report.append("\t\t");
			accuracy = cumulated_accuracy / (totalValidFields);
			if (accuracy > 1)
				accuracy = 1.0;
			report.append("\t").append(TextUtilities.formatTwoDecimals(accuracy * 100));

			precision = cumulated_precision / totalValidFields;
			if (precision > 1)
				precision = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(precision * 100));

			recall = cumulated_recall / totalValidFields;
			if (recall > 1)
				recall = 1.0;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(recall * 100));

			f0 = cumulated_f0 / totalValidFields;
			report.append("\t\t").append(TextUtilities.formatTwoDecimals(f0 * 100));

			report.append("\t(macro average)");
			report.append("\n");
			*/
			
			// instance: separated by a new line in the result file
			theResult = theResult.replace("\n\n", "\n \n");
			stt = new StringTokenizer(theResult, "\n");
			allGood = true;
			int correctInstance = 0;
			int totalInstance = 0;
			e = 0;
			while (stt.hasMoreTokens()) {
				line = stt.nextToken();
				if ((line.trim().length() == 0) || (!stt.hasMoreTokens())) {
					totalInstance++;
					if (allGood) {
						correctInstance++;
					}
					allGood = true;
				} else {
					StringTokenizer st = new StringTokenizer(line, "\t ");
					String currentToken = null;
					String previousToken = null;
					while (st.hasMoreTokens()) {
						currentToken = st.nextToken();
						if (currentToken != null) {
							if (currentToken.startsWith("I-") || currentToken.startsWith("E-") || currentToken.startsWith("B-")) {
								currentToken = currentToken.substring(2, currentToken.length());
							}
						}
						if (st.hasMoreTokens()) {
							previousToken = currentToken;
						}
					}

					// it's quite bad to have something CRF engine dependent here, but hard to avoid
					if (GrobidProperties.getGrobidCRFEngine() == GrobidCRFEngine.CRFPP) {
						previousToken = expected.get(e);
						if (previousToken.startsWith("I-") || previousToken.startsWith("E-") || previousToken.startsWith("B-")) {
							previousToken = previousToken.substring(2, previousToken.length());
						}
						e++;
					}

					if (!currentToken.equals(previousToken)) {
						allGood = false;
					}
				}
			}

			report.append("\n===== Instance-level results =====\n\n");
			report.append(String.format("%-27s %d\n", "Total expected instances:", totalInstance));
			report.append(String.format("%-27s %d\n", "Correct instances:", correctInstance));
			double accuracy = (double) correctInstance / (totalInstance);
			report.append(String.format("%-27s %s\n",
					"Instance-level recall:",
					TextUtilities.formatTwoDecimals(accuracy * 100)));
		} catch (Exception e) {
			throw new GrobidException("An exception occurred while evaluating Grobid.", e);
		}

		return report.toString();
	}

    private static void processCounters(Stats stats, String obtained, String expected) {
        boolean isNewLabel = !stats.getLabels().contains(expected);

        LabelStat expectedStat = stats.getLabelStat(expected);
        LabelStat obtainedStat = stats.getLabelStat(obtained);

        if (expected.equals(obtained)) {
            expectedStat.incrementObserved();
            expectedStat.incrementExpected();
        } else {
            expectedStat.incrementFalseNegative();

            obtainedStat.incrementFalsePositive();
            if (isNewLabel) {
                obtainedStat.incrementExpected();
            } else {
                expectedStat.incrementExpected();
            }
        }
    }

	public static String computeMetrics(Stats stats) {
		StringBuilder report = new StringBuilder();
		report.append(String.format("\n%-20s %-12s %-12s %-12s %-7s\n\n",
				"label",
				"accuracy",
				"precision",
				"recall",
				"f1"));

		int cumulated_tp = 0;
		int cumulated_fp = 0;
		int cumulated_tn = 0;
		int cumulated_fn = 0;
		double cumulated_f0 = 0.0;
		double cumulated_accuracy = 0.0;
		double cumulated_precision = 0.0;
		double cumulated_recall = 0.0;
		int cumulated_all = 0;
		int totalValidFields = 0;

		int totalFields = 0;
		for (String label : stats.getLabels()) {
			LabelStat labelStat = stats.getLabelStat(label);
			totalFields += labelStat.getExpected();
			totalFields += labelStat.getFalsePositive();
		}

		for (String label : stats.getLabels()) {
			if (label.equals("<other>") || label.equals("base") || label.equals("O")) {
				continue;
			}

			LabelStat labelStat = stats.getLabelStat(label);
			int tp = labelStat.getObserved(); // true positives
			int fp = labelStat.getFalsePositive(); // false positives
			int fn = labelStat.getFalseNegative(); // false negative
			int tn = totalFields - tp - (fp + fn); // true negatives
			int all = labelStat.getExpected(); // all expected

			if (all != 0) {
				totalValidFields++;
			}

			double accuracy = (double) (tp + tn) / (tp + fp + tn + fn);

			double precision;
			if ((tp + fp) == 0) {
				precision = 0.0;
			} else {
				precision = (double) (tp) / (tp + fp);
			}

			double recall;
			if ((tp == 0) || (all == 0)) {
				recall = 0.0;
			} else {
				recall = (double) (tp) / all;
			}

			double f0;
			if (precision + recall == 0) {
				f0 = 0.0;
			} else {
				f0 = (2 * precision * recall) / (precision + recall);
			}

			report.append(String.format("%-20s %-12s %-12s %-12s %-7s\n",
					label,
					TextUtilities.formatTwoDecimals(accuracy * 100),
					TextUtilities.formatTwoDecimals(precision * 100),
					TextUtilities.formatTwoDecimals(recall * 100),
					TextUtilities.formatTwoDecimals(f0 * 100)));

			cumulated_tp += tp;
			cumulated_fp += fp;
			cumulated_tn += tn;
			cumulated_fn += fn;
			if (all != 0) {
				cumulated_all += all;
				cumulated_f0 += f0;
				cumulated_accuracy += accuracy;
				cumulated_precision += precision;
				cumulated_recall += recall;
			}
		}

		report.append("\n");

		// micro average over measures
		double accuracy = (double) (cumulated_tp + cumulated_tn) / (cumulated_tp + cumulated_fp + cumulated_tn + cumulated_fn);
		accuracy = Math.min(1.0, accuracy);

		double precision = (double) cumulated_tp / (cumulated_tp + cumulated_fp);
		precision = Math.min(1.0, precision);

		//recall = ((double) cumulated_tp) / (cumulated_tp + cumulated_fn);
		double recall = ((double) cumulated_tp) / (cumulated_all);
		recall = Math.min(1.0, recall);

		double f0 = (2 * precision * recall) / (precision + recall);

		report.append(String.format("%-20s %-12s %-12s %-12s %-7s (micro average)\n",
				"all fields",
				TextUtilities.formatTwoDecimals(accuracy * 100),
				TextUtilities.formatTwoDecimals(precision * 100),
				TextUtilities.formatTwoDecimals(recall * 100),
				TextUtilities.formatTwoDecimals(f0 * 100)));

		// macro average over measures
		accuracy = Math.min(1.0, cumulated_accuracy / (totalValidFields));
		precision = Math.min(1.0, cumulated_precision / totalValidFields);
		recall = Math.min(1.0, cumulated_recall / totalValidFields);
		f0 = Math.min(1.0, cumulated_f0 / totalValidFields);

		report.append(String.format("%-20s %-12s %-12s %-12s %-7s (macro average)\n",
				"",
				TextUtilities.formatTwoDecimals(accuracy * 100),
				TextUtilities.formatTwoDecimals(precision * 100),
				TextUtilities.formatTwoDecimals(recall * 100),
				TextUtilities.formatTwoDecimals(f0 * 100)));

		return report.toString();
	}
}
