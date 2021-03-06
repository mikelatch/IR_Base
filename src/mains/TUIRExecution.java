package mains;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import Analyzer.BipartiteAnalyzer;
import structures.TopicModelParameter;
import structures._Corpus;
import structures._Doc;
import structures._Review;
import topicmodels.CTM.CTM;
import topicmodels.LDA.LDA_Gibbs;

import topicmodels.multithreads.LDA.LDA_Variational_multithread;
import topicmodels.multithreads.embeddingModel.TUIR_multithread;
import topicmodels.multithreads.pLSA.pLSA_multithread;
import topicmodels.pLSA.pLSA;
import Analyzer.MultiThreadedReviewAnalyzer;
import utils.Utils;

public class TUIRExecution {

	public static void main(String[] args) throws IOException {
		TopicModelParameter param = new TopicModelParameter(args);

		int classNumber = 6; //Define the number of classes in this Naive Bayes.
		int Ngram = 2; //The default value is unigram.
		int lengthThreshold = 5; //Document length threshold
		boolean setRandomFold = true;
		int numberOfCores = Runtime.getRuntime().availableProcessors();

		String tokenModel = "./data/Model/en-token.bin";
		String dataset = String.format("%s/%s/%s", param.m_prefix, param.m_source, param.m_set);
		String fvFile = String.format("./data/Features/fv_2gram_%s_%s_%s.txt", param.m_featureGenMethod, param.m_source, param.m_set);
		String reviewFolder = String.format("%s/data/", dataset);
		String outputFolder = String.format("%s/output/%dfoldsCV%s/", dataset, param.m_crossV, param.m_flag_coldstart?"Coldstart":"");

		MultiThreadedReviewAnalyzer analyzer = new MultiThreadedReviewAnalyzer(tokenModel, classNumber, fvFile,
				Ngram, lengthThreshold, numberOfCores, true, param.m_source);
		if(setRandomFold==false)
			analyzer.setReleaseContent(false);//Remember to set it as false when generating crossfolders!!!
		analyzer.loadUserDir(reviewFolder);
		_Corpus corpus = analyzer.getCorpus();

		if(param.m_crossV>1 && setRandomFold==false){
			reviewFolder = String.format("%s/%dfoldsCV%s/", dataset, param.m_crossV, param.m_flag_coldstart?"Coldstart":"");
			//if no data, generate
            String cvFolder = String.format("%s/0/", reviewFolder);
			File testFile = new File(cvFolder);
			if(!testFile.exists() && !testFile.isDirectory()){
				System.err.format("[Warning]Cross validation dataset %s not exist! Now generating...", cvFolder);
				BipartiteAnalyzer cv = new BipartiteAnalyzer(corpus); // split corpus into folds
				cv.analyzeCorpus();
				if(param.m_flag_coldstart)
				    cv.splitCorpusColdStart(param.m_crossV, reviewFolder);
				else
                    cv.splitCorpus(param.m_crossV, reviewFolder);
			}
		}

        int result_dim = 1;
		pLSA tModel = null;
		long current = System.currentTimeMillis();
		if (param.m_topicmodel.equals("pLSA")) {
			tModel = new pLSA_multithread(param.m_emIter, param.m_emConverge, param.m_beta, corpus,
					param.m_lambda, param.m_number_of_topics, param.m_alpha);
		} else if (param.m_topicmodel.equals("LDA_Gibbs")) {
			tModel = new LDA_Gibbs(param.m_emIter, param.m_emConverge, param.m_beta, corpus,
					param.m_lambda, param.m_number_of_topics, param.m_alpha, 0.4, 50);
		}  else if (param.m_topicmodel.equals("LDA_Variational")) {
			tModel = new LDA_Variational_multithread(param.m_emIter, param.m_emConverge, param.m_beta, corpus,
					param.m_lambda, param.m_number_of_topics, param.m_alpha, param.m_varMaxIter, param.m_varConverge);
		} else if(param.m_topicmodel.equals("TUIR") || param.m_topicmodel.equals("TUIR_User") || param.m_topicmodel.equals("TUIR_Item")){
			tModel = new TUIR_multithread(param.m_emIter, param.m_emConverge, param.m_beta, corpus, param.m_lambda,
					param.m_number_of_topics, param.m_alpha, param.m_varMaxIter, param.m_varConverge, param.m_sigma, param.m_rho);
			if(param.m_topicmodel.equals("TUIR_User"))
			    ((TUIR_multithread) tModel).setMode("User");
			else if(param.m_topicmodel.equals("TUIR_Item"))
			    ((TUIR_multithread) tModel).setMode("Item");

			((TUIR_multithread) tModel).setFlagGd(param.m_flag_gd);
            ((TUIR_multithread) tModel).setFlagLambda(param.m_flag_fix_lambda);

            result_dim = 5;
		} else if(param.m_topicmodel.equals("CTM")){
			tModel = new CTM(param.m_emIter, param.m_emConverge, param.m_beta, corpus,
					param.m_lambda, param.m_number_of_topics, param.m_alpha, param.m_varMaxIter, param.m_varConverge);
		} else{
			System.out.println("The selected topic model has not developed yet!");
			return;
		}

        tModel.setDisplayLap(1);
        new File(outputFolder).mkdirs();
        tModel.setInforWriter(outputFolder + param.m_topicmodel + "_info.txt");
        if (param.m_crossV<=1) {//just train
            tModel.EMonCorpus();
            tModel.printParameterAggregation(param.m_topk, outputFolder, param.m_topicmodel);
            tModel.closeWriter();
        } else if(setRandomFold == true){//cross validation with random folds
            tModel.setRandomFold(setRandomFold);
            double trainProportion = ((double)param.m_crossV - 1)/(double)param.m_crossV;
            double testProportion = 1-trainProportion;
            tModel.setPerplexityProportion(testProportion);
            tModel.crossValidation(param.m_crossV);
        } else{//cross validation with fixed folds
            double[][] perf = new double[param.m_crossV][result_dim];
            double[][] like = new double[param.m_crossV][result_dim];
            System.out.println("[Info]Start FIXED cross validation...");
            for(int k = 0; k <param.m_crossV; k++){
                analyzer.getCorpus().reset();
                //load test set
                String testFolder = reviewFolder + k + "/";
                analyzer.loadUserDir(testFolder);
                for(_Doc d : analyzer.getCorpus().getCollection()){
                    d.setType(_Review.rType.TEST);
                }
                //load train set
                for(int i = 0; i < param.m_crossV; i++){
                    if(i!=k){
                        String trainFolder = reviewFolder + i + "/";
                        analyzer.loadUserDir(trainFolder);
                    }
                }
                tModel.setCorpus(analyzer.getCorpus());

                System.out.format("====================\n[Info]Fold No. %d: \n", k);
                double[] results = tModel.oneFoldValidation();
                for(int i = 0; i < result_dim; i++){
                    perf[k][i] = results[2*i];
                    like[k][i] = results[2*i+1];
                }

                String resultFolder = outputFolder + k + "/";
                new File(resultFolder).mkdirs();
                tModel.printParameterAggregation(param.m_topk, resultFolder, param.m_topicmodel);
                tModel.printTopWords(param.m_topk);

                if(param.m_flag_tune){
                    System.out.format("[Info]Tuning mode: only run one fold to save time.\n");
                    break;
                }
            }

            //output the performance statistics
            System.out.println();
            double mean = 0, var = 0;
            int[] invalid_label = new int[like.length];
            for(int j = 0; j < result_dim; j++) {
                System.out.format("Part %d -----------------", j);
                Arrays.fill(invalid_label, 0);
                for (int i = 0; i < like.length; i++) {
                    if (Double.isNaN(like[i][j]) || Double.isNaN(perf[i][j]) || perf[i][j] <= 0 )
                        invalid_label[i]=1;
                }
                int validLen = like.length - Utils.sumOfArray(invalid_label);
                System.out.format("Valid folds: %d\n", validLen);

                mean=0;
                var=0;
                for (int i = 0; i < like.length; i++) {
                    if (invalid_label[i]<1)
                        mean += like[i][j];
                }
                if(validLen>0)
                    mean /= validLen;
                for (int i = 0; i < like.length; i++) {
                    if (invalid_label[i]<1)
                        var += (like[i][j] - mean) * (like[i][j] - mean);
                }
                if(validLen>0)
                    var = Math.sqrt(var / validLen);
                System.out.format("[Stat]Loglikelihood %.3f+/-%.3f\n", mean, var);

                mean = 0;
                var = 0;
                for (int i = 0; i < perf.length; i++) {
                    if (invalid_label[i]<1)
                        mean += perf[i][j];
                }
                if(validLen>0)
                    mean /= validLen;
                for (int i = 0; i < perf.length; i++) {
                    if (invalid_label[i]<1)
                        var += (perf[i][j] - mean) * (perf[i][j] - mean);
                }
                if(validLen>0)
                    var = Math.sqrt(var / validLen);
                System.out.format("[Stat]Perplexity %.3f+/-%.3f\n", mean, var);
            }
        }
    }
}