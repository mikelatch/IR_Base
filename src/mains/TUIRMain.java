package mains;

import java.io.*;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Set;

import Analyzer.BipartiteAnalyzer;
import Analyzer.MultiThreadedReviewAnalyzer;
import structures._Corpus;
import structures._Doc;
import structures._Review;
import topicmodels.CTM.CTM;
import topicmodels.LDA.LDA_Gibbs;
import topicmodels.multithreads.LDA.LDA_Variational_multithread;
import topicmodels.multithreads.embeddingModel.TUIR_multithread;
import topicmodels.multithreads.pLSA.pLSA_multithread;
import topicmodels.pLSA.pLSA;


/**
 * @author Lu Lin
 */
public class TUIRMain {

    public static void main(String[] args) throws IOException, ParseException {
        /***text segmentation setting*****/
        int classNumber = 6; //Define the number of classes in this Naive Bayes.
        int Ngram = 2; //The default value is unigram.
        int lengthThreshold = 5; //Document length threshold
        int numberOfCores = Runtime.getRuntime().availableProcessors();
        String tokenModel = "./data/Model/en-token.bin";

        /*****parameters for topic model*****/
        String topicmodel = "LDA_Variational"; // CTM, LDA_Variational, TUIR, TUIR_User, TUIR_Item
        int number_of_topics = 30;
        int varMaxIter = 50;
        double varConverge = 1e-6;
        int emMaxIter = 50;
        double emConverge = 1e-10;

        double alpha = 1 + 1e-2, beta = 1 + 1e-3, lambda = 1 + 1e-3;//these two parameters must be larger than 1!!!
        double sigma = 0.1, rho = 0.1;

        int topK = 50;
        int crossV = 1;
        boolean setRandomFold = true;//whether dynamically generate folds or fix it
        boolean flag_coldstart = false;//whether split folds with cold start user and item

        /*****data setting*****/
        String trainset = "Processed";
        String source = "10PerStackOverflow";
        String dataset = "../IR_Data/" + source + "/" + trainset + "/";
        String outputFolder = String.format("%soutput/%dfoldsCV%s/", dataset, crossV, flag_coldstart?"Coldstart":"");

        /*****vocabulary setting*****/
        String[] fvFiles = new String[5];
        fvFiles[0] = "./data/Features/fv_2gram_IG_yelp_byUser_30_50_25.txt";
        fvFiles[1] = "./data/Features/fv_2gram_IG_amazon_movie_byUser_40_50_12.txt";
        fvFiles[2] = "./data/Features/fv_2gram_IG_amazon_electronic_byUser_20_20_5.txt";
        fvFiles[3] = "./data/Features/fv_2gram_IG_amazon_book_byUser_40_50_12.txt";
        fvFiles[4] = "./data/Features/fv_2gram_IG_10PerStackOverflow_Processed.txt";
        int fvFile_point = 0;
        if(source.equals("amazon_movie")){
            fvFile_point = 1;
        }else if(source.equals("amazon_electronic")){
            fvFile_point = 2;
        }else if(source.equals("amazon_book")){
            fvFile_point = 3;
        }else if(source.equals("10PerStackOverflow")){
            fvFile_point = 4;
        }

        String reviewFolder = dataset + "data/"; //2foldsCV/folder0/train/, data/
        MultiThreadedReviewAnalyzer analyzer = new MultiThreadedReviewAnalyzer(tokenModel, classNumber, fvFiles[fvFile_point],
                Ngram, lengthThreshold, numberOfCores, true, source);
        if(setRandomFold==false)
            analyzer.setReleaseContent(false);//Remember to set it as false when generating crossfolders!!!
        analyzer.loadUserDir(reviewFolder);
        _Corpus corpus = analyzer.getCorpus();

        if(setRandomFold==false){
            reviewFolder = String.format("%s%dfoldsCV%s/", dataset, crossV, flag_coldstart?"Coldstart":"");
            //if no data, generate
            File testFile = new File(reviewFolder + 0 + "/");
            if(!testFile.exists() && !testFile.isDirectory()){
                System.err.println("[Warning]Cross validation dataset not exist! Now generating...");
                BipartiteAnalyzer cv = new BipartiteAnalyzer(corpus); // split corpus into folds
                cv.analyzeCorpus();
                if(flag_coldstart)
                    cv.splitCorpusColdStart(crossV, reviewFolder);
                else
                    cv.splitCorpus(crossV,reviewFolder);
            }
        }
//        corpus.save2File(dataset + "yelp_4k.dat");//format document for CTM/RTM

        /*****model loading*****/
        int result_dim = 1;
        pLSA tModel = null;
        if (topicmodel.equals("pLSA")) {
            tModel = new pLSA_multithread(emMaxIter, emConverge, beta, corpus,
                    lambda, number_of_topics, alpha);
        } else if (topicmodel.equals("LDA_Gibbs")) {
            tModel = new LDA_Gibbs(emMaxIter, emConverge, beta, corpus,
                    lambda, number_of_topics, alpha, 0.4, 50);
        }  else if (topicmodel.equals("LDA_Variational")) {
            tModel = new LDA_Variational_multithread(emMaxIter, emConverge, beta, corpus,
                    lambda, number_of_topics, alpha, varMaxIter, varConverge); //set this negative!! or likelihood will not change
        } else if (topicmodel.equals("TUIR") || topicmodel.equals("TUIR_User") || topicmodel.equals("TUIR_Item")){
            tModel = new TUIR_multithread(emMaxIter, emConverge, beta, corpus, lambda,
                    number_of_topics, alpha, varMaxIter, varConverge, sigma, rho);
            if(topicmodel.equals("TUIR_User"))
                ((TUIR_multithread) tModel).setMode("User");
            else if(topicmodel.equals("TUIR_Item"))
                ((TUIR_multithread) tModel).setMode("Item");

            result_dim = 5;//coldstartAll, coldstartUser, coldstartItem, warmstartAll, overall
        } else if(topicmodel.equals("CTM")){
            tModel = new CTM(emMaxIter, emConverge, beta, corpus,
                    lambda, number_of_topics, alpha, varMaxIter, varConverge);
        } else {
            System.out.println("The selected topic model has not developed yet!");
            return;
        }

        tModel.setDisplayLap(1);
        new File(outputFolder).mkdirs();
        tModel.setInforWriter(outputFolder + topicmodel + "_info.txt");
        if (crossV<=1) {//just train
            tModel.EMonCorpus();
            tModel.printParameterAggregation(topK, outputFolder, topicmodel);
            tModel.closeWriter();
        } else if(setRandomFold == true){//cross validation with dynamically generated random folds
            tModel.setRandomFold(setRandomFold);
            double trainProportion = ((double)crossV - 1)/(double)crossV;
            double testProportion = 1-trainProportion;
            tModel.setPerplexityProportion(testProportion);
            tModel.crossValidation(crossV);
        } else{//cross validation with fixed folds
            double[][] perf = new double[crossV][result_dim];
            double[][] like = new double[crossV][result_dim];
            System.out.println("[Info]Start FIXED cross validation...");
            for(int k = 0; k <crossV; k++){
                analyzer.getCorpus().reset();
                //load test set
                String testFolder = reviewFolder + k + "/";
                analyzer.loadUserDir(testFolder);
                for(_Doc d : analyzer.getCorpus().getCollection()){
                    d.setType(_Review.rType.TEST);
                }
                //load train set
                for(int i = 0; i < crossV; i++){
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
                tModel.printParameterAggregation(topK, resultFolder, topicmodel);
            }

            //output the performance statistics
            System.out.println();
            double mean = 0, var = 0;
            for(int j = 0; j < result_dim; j++) {
                System.out.format("Part %d -----------------", j);
                Set invalid = new HashSet();
                for (int i = 0; i < like.length; i++) {
                    if (Double.isNaN(like[i][j]) || Double.isNaN(perf[i][j]) || perf[i][j] <= 0 )
                        invalid.add(i);
                }
                int validLen = like.length - invalid.size();
                System.out.format("Valid folds: %d\n", validLen);

                mean=0;
                var=0;
                for (int i = 0; i < like.length; i++) {
                    if (!invalid.contains(i))
                        mean += like[i][j];
                }
                if(validLen>0)
                    mean /= validLen;
                for (int i = 0; i < like.length; i++) {
                    if (!invalid.contains(i))
                        var += (like[i][j] - mean) * (like[i][j] - mean);
                }
                if(validLen>0)
                    var = Math.sqrt(var / validLen);
                System.out.format("[Stat]Loglikelihood %.3f+/-%.3f\n", mean, var);

                mean = 0;
                var = 0;
                for (int i = 0; i < perf.length; i++) {
                    if (!invalid.contains(i))
                        mean += perf[i][j];
                }
                if(validLen>0)
                    mean /= validLen;
                for (int i = 0; i < perf.length; i++) {
                    if (!invalid.contains(i))
                        var += (perf[i][j] - mean) * (perf[i][j] - mean);
                }
                if(validLen>0)
                    var = Math.sqrt(var / validLen);
                System.out.format("[Stat]Perplexity %.3f+/-%.3f\n", mean, var);
            }
        }
    }
}