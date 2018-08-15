package topicmodels.embeddingModel;

import java.io.*;
import java.util.*;

import Analyzer.BipartiteAnalyzer;
import hep.aida.tdouble.DoubleIAxis;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import structures.*;
import topicmodels.LDA.LDA_Variational;
import topicmodels.markovmodel.HTSM;
import topicmodels.multithreads.LDA.LDA_Focus_multithread;
import topicmodels.multithreads.TopicModelWorker;
import utils.Utils;
import LBFGS.LBFGS;

import javax.rmi.CORBA.Util;

/**
 * @author Lu Lin
 * Variational inference for Explainable Topic-Based Item Recommendation (ETBIR) model
 */
public class ETBIR extends LDA_Variational {
    final static double log2PI = Math.log(2*Math.PI);

    protected int number_of_users;
    protected int number_of_items;
    protected int len1;
    protected int len2;

    protected List<_User> m_users;
    protected List<_Product> m_items;

    protected HashMap<String, Integer> m_usersIndex; //(userID, index in m_users)
    protected HashMap<String, Integer> m_itemsIndex; //(itemID, index in m_items)
    protected HashMap<String, Integer> m_reviewIndex; //(itemIndex_userIndex, index in m_corpus.m_collection)

    //training set of users and items
    protected HashMap<Integer, ArrayList<Integer>>  m_mapByUser; //adjacent list for user, controlled by m_testFlag.
    protected HashMap<Integer, ArrayList<Integer>> m_mapByItem;

    //testing set of users and items
    protected HashMap<Integer, ArrayList<Integer>> m_mapByUser_test; //test
    protected HashMap<Integer, ArrayList<Integer>> m_mapByItem_test;

    protected BipartiteAnalyzer m_bipartite;

    //this is variance parameter for the model
    protected double m_rho;
    protected double m_sigma;

    protected double m_pStats;
    protected double m_thetaStats;
    protected double m_eta_p_Stats;
    protected double m_eta_mean_Stats;
    protected double m_lambda_Stats;

    double d_mu = 1.0, d_sigma_theta = 1.0;
    double d_nu = 1.0, d_sigma_P = 1.0;

    protected String m_mode;
    protected boolean m_flag_fix_lambda;
    protected boolean m_flag_diagonal_lambda;
    protected boolean m_flag_gd;

    //coldstart_all, coldstart_user, coldstart_item, normal;
    protected double[] m_likelihood_array;
    protected double[] m_perplexity_array;
    protected double[] m_totalWords_array;
    protected double[] m_docSize_array;

    public ETBIR(int emMaxIter, double emConverge,
                 double beta, _Corpus corpus, double lambda,
                 int number_of_topics, double alpha, int varMaxIter, double varConverge, //LDA_variational
                 double sigma, double rho) {
        super(emMaxIter, emConverge,
                beta, corpus, lambda,
                number_of_topics, alpha, varMaxIter, varConverge);

        this.m_sigma = sigma;
        this.m_rho = rho;
        this.m_mode = "Normal";
        this.m_flag_fix_lambda = false;
        this.m_flag_diagonal_lambda = false;
        this.m_flag_gd = false;
        m_logSpace = true;
    }

    public void setMode(String mode){
        this.m_mode = mode;
    }

    public void setFlagLambda(boolean flagLambda){ this.m_flag_fix_lambda = flagLambda; }

    public void setFlagDiagonal(boolean flagDiagonal){ this.m_flag_diagonal_lambda = flagDiagonal; }

    public void setFlagGd(boolean flagGd){ this.m_flag_gd = flagGd; }

    @Override
    protected void createSpace() {
        super.createSpace();

        this.len1 = number_of_topics;
        this.len2 = number_of_topics;
        m_alpha = new double[len2];
        m_alphaStat = new double[len2];
        m_alphaG = new double[len2];
        m_alphaH = new double[len2];

        Arrays.fill(m_alpha, d_alpha);
    }

    @Override
    public String toString(){
        return String.format("ETBIR_%s[k:%d, alpha:%.5f, beta:%.5f, simga:%.5f, rho:%.5f, item~N(%.5f, %.5f), user~N(%.5f, %.5f)]\n",
                m_mode, number_of_topics, d_alpha, d_beta, this.m_sigma, this.m_rho, d_mu, d_sigma_theta, d_nu, d_sigma_P);
    }

    @Override
    protected void init() { // clear up for next iteration during EM
        super.init();

        m_pStats = 0.0;
        m_thetaStats = 0.0;
        m_eta_p_Stats = 0.0;
        m_eta_mean_Stats = 0.0;
        m_lambda_Stats = 0.0;
    }

    protected void updateStats4Item(_Product4ETBIR item){
        double digammaSum = Utils.digamma(Utils.sumOfArray(item.m_eta));
        for(int k = 0; k < len2; k++)
            m_alphaStat[k] += Utils.digamma(item.m_eta[k]) - digammaSum;
    }

    protected void updateStats4User(_User4ETBIR user){
        for(int k = 0; k < len2; k++){
            for(int l = 0; l < len2; l++){
                if(!m_flag_diagonal_lambda)
                    m_pStats += user.m_SigmaP[k][l][l] + user.m_nuP[k][l] * user.m_nuP[k][l]
                            - 2 * m_lambda * Utils.sumOfArray(user.m_nuP[k]) + m_lambda * m_lambda * number_of_topics;
                else
                    m_pStats += user.m_SigmaP[k][l][l] + user.m_nuP[k][l] * user.m_nuP[k][l] - 2 * m_lambda * user.m_nuP[k][k] + m_lambda * m_lambda;
            }
            if(!m_flag_diagonal_lambda)
                m_lambda_Stats += Utils.sumOfArray(user.m_nuP[k]);
            else
                m_lambda_Stats += user.m_nuP[k][k];
        }
    }

    protected void updateStats4Doc(_Doc4ETBIR doc){
        // update m_word_topic_stats for updating beta
        _SparseFeature[] fv = doc.getSparse();
        int wid;
        double v;
        for(int n=0; n<fv.length; n++) {
            wid = fv[n].getIndex();
            v = fv[n].getValue();
            for(int i=0; i<number_of_topics; i++)
                word_topic_sstat[i][wid] += v * doc.m_phi[n][i];
        }

        // update m_thetaStats for updating rho
        for(int k = 0; k < number_of_topics; k++)
            m_thetaStats += doc.m_Sigma[k] + doc.m_mu[k] * doc.m_mu[k];

        // update m_eta_p_stats for updating rho
        // update m_eta_mean_stats for updating rho
        double eta_mean_temp = 0.0, eta_p_temp = 0.0;
        _Product4ETBIR item = (_Product4ETBIR) m_items.get(m_itemsIndex.get(doc.getItemID()));
        _User4ETBIR user = (_User4ETBIR) m_users.get(m_usersIndex.get(doc.getUserID()));
        for (int k = 0; k < number_of_topics; k++) {
            for (int l = 0; l < number_of_topics; l++) {
                eta_mean_temp += item.m_eta[l] * user.m_nuP[k][l] * doc.m_mu[k];

                for (int j = 0; j < number_of_topics; j++) {
                    double term1 = user.m_SigmaP[k][l][j] + user.m_nuP[k][l] * user.m_nuP[k][j];
                    eta_p_temp += item.m_eta[l] * item.m_eta[j] * term1;
                    if (j == l)
                        eta_p_temp += item.m_eta[l] * term1;
                }
            }
        }

        double eta0 = Utils.sumOfArray(item.m_eta);
        m_eta_mean_Stats += eta_mean_temp / eta0;
        m_eta_p_Stats += eta_p_temp / (eta0 * (eta0 + 1.0));
    }

    protected double E_step(){//problematic!!!
        int iter = 0, docIndex = 0;
        double totalLikelihood = 0.0, last = -1.0, converge = 0.0;
        _Doc4ETBIR d;
        _User4ETBIR u;
        _Product4ETBIR i;

        init();
        boolean warning;
        do {
            warning=false;
            totalLikelihood = 0.0;
            for (_Doc doc:m_trainSet) {
                d = (_Doc4ETBIR)doc;

                String userID = d.getUserID();
                String itemID = d.getItemID();
                u = (_User4ETBIR) m_users.get(m_usersIndex.get(userID));
                i = (_Product4ETBIR) m_items.get(m_itemsIndex.get(itemID));

                totalLikelihood += varInference4Doc(d, u, i);
            }

            for (int u_idx : m_mapByUser.keySet()) {
                u = (_User4ETBIR) m_users.get(u_idx);
                totalLikelihood += varInference4User(u);
            }


            for (int i_idx : m_mapByItem.keySet()) {
                i = (_Product4ETBIR) m_items.get(i_idx);
                totalLikelihood += varInference4Item(i);
            }

            if(Double.isNaN(totalLikelihood) || Double.isInfinite(totalLikelihood))
                warning = true;

            if(iter > 0)
                converge = Math.abs((totalLikelihood - last) / last);
            else
                converge = 1.0;

            last = totalLikelihood;

            if(iter % 10 == 0)
                System.out.format("[Info]Single-thread E-Step: %d iteration, likelihood=%.2f, converge to %.8f\n",
                    iter, last, converge);

        }while(iter++ < m_varMaxIter*2 && converge > m_varConverge/3 && !warning);

        //collect sufficient statistics for model update
        if (m_collectCorpusStats) {
            for (_Doc doc:m_trainSet)
                updateStats4Doc((_Doc4ETBIR)doc);

            for (int u_idx:m_mapByUser.keySet())
                updateStats4User((_User4ETBIR) m_users.get(u_idx));

            for (int i_idx:m_mapByItem.keySet())
                updateStats4Item((_Product4ETBIR) m_items.get(i_idx));
        }

        return totalLikelihood;
    }

    public double inference(_User4ETBIR user) {
//        user.setTopics4Variational(number_of_topics, d_nu, d_sigma_P);
        return varInference4User(user);
    }

    public double inference(_Product4ETBIR item){
//        item.setTopics4Variational(number_of_topics, d_alpha+1);
        return varInference4Item(item);
    }

    @Override
    protected void initTestDoc(_Doc d) {
        ((_Doc4ETBIR) d).setTopics4Variational(number_of_topics, d_alpha, d_mu, d_sigma_theta);
    }

    protected void initTestUser(_User4ETBIR user){
        user.setTopics4Variational(number_of_topics, d_nu, d_sigma_P);
    }

    protected void initTestItem(_Product4ETBIR item){
        item.setTopics4Variational(number_of_topics, d_alpha);
    }

    protected double varInference4User(_User4ETBIR u){
        // since updating nu will not influence sigmaP, we do not need loop outside
        if(!m_mode.equals("Item")) {
            update_SigmaP(u);
            update_nu(u);
        }
        return calc_log_likelihood_per_user(u);
    }

    protected double varInference4Item(_Product4ETBIR i){
        int itemIdx = m_itemsIndex.get(i.getID());

        double[] pNuStats = new double[number_of_topics];
        double[][] pSumStats = new double[number_of_topics][number_of_topics];

        ArrayList<Integer> Ui = new ArrayList<>();
        if(m_mapByItem.containsKey(itemIdx))
            m_mapByItem.get(itemIdx);//all users associated with this item

        for (Integer userIdx : Ui) {
            _User4ETBIR user = (_User4ETBIR) m_users.get(userIdx);
            _Doc4ETBIR doc = (_Doc4ETBIR) m_corpus.getCollection().get(m_reviewIndex.get(itemIdx + "_" + userIdx));
            for(int k = 0; k < number_of_topics; k++){
                for(int l = 0; l < number_of_topics; l++){
                    pNuStats[k] += user.m_nuP[l][k] * doc.m_mu[l];

                    for (int j = 0; j < number_of_topics; j++)
                        pSumStats[k][l] += user.m_SigmaP[j][l][k] + user.m_nuP[j][k] * user.m_nuP[j][l];
                }
            }
        }

        if(!m_mode.equals("User"))
            update_eta(i, pNuStats, pSumStats);

        return calc_log_likelihood_per_item(i);
    }

    protected double varInference4Doc(_Doc4ETBIR d, _User4ETBIR u, _Product4ETBIR i) {
        double current = 0.0, last = 1.0, converge = 0.0;
        int iter = 0;

        boolean warning;
        do {
            warning = false;
            update_phi(d);
            update_zeta(d);
            update_mu(d, u ,i);
            update_zeta(d);
            update_SigmaTheta(d);
            update_zeta(d);
//            update_mu_sigmaTheta(d, u ,i);

            current = calc_log_likelihood_per_doc(d, u, i);

            if(Double.isNaN(current) || Double.isInfinite(current))
                warning = true;

            if (iter > 0)
                converge = (last-current) / last;
            else
                converge = 1.0;

            last = current;
        } while (++iter < m_varMaxIter && Math.abs(converge) > m_varConverge && !warning);

        return current;
    }


    //variational inference for p(z|w,\phi) for each document
    void update_phi(_Doc4ETBIR d){
        double logSum;
        int wid;
        _SparseFeature[] fv = d.getSparse();

        Arrays.fill(d.m_sstat, 0);
        for (int n = 0; n < fv.length; n++) {
            wid = fv[n].getIndex();
            for (int k = 0; k < number_of_topics; k++)
                d.m_phi[n][k] = topic_term_probabilty[k][wid] + d.m_mu[k];

            // normalize
            logSum = Utils.logSum(d.m_phi[n]);
            for (int k = 0; k < number_of_topics; k++) {
                d.m_phi[n][k] = Math.exp(d.m_phi[n][k] - logSum);
                d.m_sstat[k] += fv[n].getValue() * d.m_phi[n][k];
            }
        }
    }

    //variational inference for p(\theta|\mu,\Sigma) for each document
    void update_zeta(_Doc4ETBIR d){
        //estimate zeta in log space
        d.m_logZeta = d.m_mu[0] + 0.5 * d.m_Sigma[0];
        for (int k = 1; k < number_of_topics; k++)
            d.m_logZeta = Utils.logSum(d.m_logZeta, d.m_mu[k] + 0.5 * d.m_Sigma[k]);
    }

    // alternative: line search / fixed-stepsize gradient descent
    void update_mu(_Doc4ETBIR doc, _User4ETBIR user, _Product4ETBIR item){
        double fValue = 1.0, lastFValue = 1.0, cvg = 1e-6, diff, iterMax = 30, iter = 0;
        double stepsize = 1e-4, muG; // gradient for mu
        int N = doc.getTotalDocLength();

        double moment, norm, check1, check2, check3;
        double etaSum = Utils.sumOfArray(item.m_eta);

        double[] muH = new double[number_of_topics];
        Arrays.fill(muH, 1.0);
        boolean warning;
        do {
            warning = false;
            //update gradient of mu
            lastFValue = fValue;
            fValue = 0.0;
            for (int k = 0; k < number_of_topics; k++) {
                moment = N * Math.exp(doc.m_mu[k] + 0.5 * doc.m_Sigma[k]-doc.m_logZeta);
                norm = Utils.dotProduct(item.m_eta, user.m_nuP[k]) / etaSum;

                muG = -m_rho * (doc.m_mu[k] - norm)
                        + doc.m_sstat[k] - moment;

                fValue += -0.5 * m_rho * (doc.m_mu[k] * doc.m_mu[k] - 2 * doc.m_mu[k] * norm)
                        + doc.m_mu[k] * doc.m_sstat[k] - moment;

                if(m_flag_gd)
                    doc.m_mu[k] += stepsize * muG;//fixed stepsize
                else
                    doc.m_mu[k] += stepsize/Math.sqrt(muH[k]) * muG;//ada gradient
                muH[k] += muG * muG;

                if (Double.isNaN(fValue) || Double.isInfinite(fValue)) {
                    warning = true;
                    break;
                }
            }

            diff = (lastFValue - fValue) / lastFValue;
        } while (!warning && iter++ < iterMax && Math.abs(diff) > cvg);
    }

    private void update_SigmaTheta(_Doc4ETBIR d){
        double fValue = 1.0, lastFValue = 1.0, cvg = 1e-6, diff, iterMax = 20, iter = 0;
        double stepsize = 1e-4, moment;
        double sigmaG; // gradient for Sigma
        int N = d.getTotalDocLength();

        double[] sigmaH = new double[number_of_topics];
        Arrays.fill(sigmaH, 1.0);

        for(int k=0; k < number_of_topics; k++)
            d.m_sigmaSqrt[k] = Math.sqrt(d.m_Sigma[k]);

        boolean warning;
        do {
            warning = false;

            //update gradient of sigma
            lastFValue = fValue;
            fValue = 0.0;

            for (int k = 0; k < number_of_topics; k++) {
                moment = Math.exp(d.m_mu[k] + 0.5 * d.m_Sigma[k] - d.m_logZeta);
                sigmaG = -m_rho * d.m_sigmaSqrt[k] - N * d.m_sigmaSqrt[k] * moment
                        + 1.0 / d.m_sigmaSqrt[k];
                fValue += -0.5 * m_rho * d.m_Sigma[k] - N * moment + 0.5 * Math.log(d.m_Sigma[k]);

                if(m_flag_gd)
                    d.m_sigmaSqrt[k] += stepsize * sigmaG;//fixed stepsize
                else
                    d.m_sigmaSqrt[k] += stepsize/Math.sqrt(sigmaH[k]) * sigmaG;//ada gradient
                d.m_Sigma[k] = d.m_sigmaSqrt[k] * d.m_sigmaSqrt[k];

                sigmaH[k] += sigmaG * sigmaG;

                if (Double.isNaN(fValue) || Double.isInfinite(fValue)) {
                    warning = true;
                    break;
                }
            }

            diff = (lastFValue - fValue) / lastFValue;
        } while(!warning && iter++ < iterMax && Math.abs(diff) > cvg);
    }

    // alternative: line search / fixed-stepsize gradient descent
    void update_mu_sigmaTheta(_Doc4ETBIR doc, _User4ETBIR user, _Product4ETBIR item){
        double fValue = 1.0, lastFValue, cvg = 1e-3, diff, iterMax = 10, iter = 0;
        double stepsize = 1e-2, muG, sigmaG;
        double N = doc.getTotalDocLength(), logN = Math.log(N);

        double moment, norm;
        double etaSum = Utils.sumOfArray(item.m_eta);

        double[] muH = new double[number_of_topics];
        double[] sigmaH = new double[number_of_topics];

        Arrays.fill(muH, 1.0);
        Arrays.fill(sigmaH, 1.0);
        for(int k=0; k < number_of_topics; k++)
            doc.m_sigmaSqrt[k] = Math.sqrt(doc.m_Sigma[k]);

        boolean warning;
        do {
            warning = false;

            //update gradient of mu
            lastFValue = fValue;
            fValue = 0.0;
            for (int k = 0; k < number_of_topics; k++) {
                moment = Math.exp(logN + doc.m_mu[k] + 0.5 * doc.m_Sigma[k] - doc.m_logZeta);
                norm = Utils.dotProduct(item.m_eta, user.m_nuP[k]) / etaSum;

                muG = -m_rho*(doc.m_mu[k] - norm) + doc.m_sstat[k] - moment;
                sigmaG = -m_rho*doc.m_sigmaSqrt[k] - doc.m_sigmaSqrt[k]*moment + 1.0/doc.m_sigmaSqrt[k];

                doc.m_mu[k] += stepsize/Math.sqrt(muH[k]) * muG;//ada gradient
                doc.m_sigmaSqrt[k] += stepsize/Math.sqrt(sigmaH[k]) * sigmaG;//ada gradient
                doc.m_Sigma[k] = doc.m_sigmaSqrt[k] * doc.m_sigmaSqrt[k];

                fValue += -0.5 * m_rho * (doc.m_Sigma[k] + doc.m_mu[k] * doc.m_mu[k] - 2 * doc.m_mu[k] * norm)
                        + doc.m_mu[k] * doc.m_sstat[k]
                        - moment + 0.5 * Math.log(doc.m_Sigma[k]);

                muH[k] += muG * muG;
                sigmaH[k] += sigmaG * sigmaG;

                if (Math.abs(doc.m_mu[k])>10) {
                    System.err.format("[Warning]%s has a potentially too large mu: %.3f!\n", doc.getID(), doc.m_mu[k]);
                    warning = true;
                }
                if (Math.abs(doc.m_sigmaSqrt[k])>5) {
                    System.err.format("[Warning]%s has a potentially too large Sigma: %.3f!\n", doc.getID(), doc.m_sigmaSqrt[k]*doc.m_sigmaSqrt[k]);
                    warning = true;
                }
            }

            diff = (lastFValue - fValue) / lastFValue;
            update_zeta(doc);
        } while (!warning && iter++ < iterMax && Math.abs(diff) > cvg);

//        if (iter>=iterMax)
//        	System.out.print("x");//fail to converge
//        else
//        	System.out.print("o");//converge in likelihood

    }

    //variational inference for p(P|\nu,\Sigma) for each user
    private void update_SigmaP(_User4ETBIR u){
        int idx = m_usersIndex.get(u.getUserID());
        ArrayList<Integer> Iu = new ArrayList<>();
        if(m_mapByUser.containsKey(idx))
            Iu = m_mapByUser.get(idx);//all the items reviewed by this user

        RealMatrix eta_stat_sigma = MatrixUtils.createRealIdentityMatrix(number_of_topics).scalarMultiply(m_sigma);
        for (Integer itemIdx : Iu) {
            _Product4ETBIR item = (_Product4ETBIR) m_items.get(itemIdx);

            RealMatrix eta_vec = MatrixUtils.createColumnRealMatrix(item.m_eta);
            double eta0 = Utils.sumOfArray(item.m_eta);
            RealMatrix eta_stat_i = MatrixUtils.createRealDiagonalMatrix(item.m_eta).add(eta_vec.multiply(eta_vec.transpose()));

            eta_stat_sigma = eta_stat_sigma.add(eta_stat_i.scalarMultiply(m_rho / (eta0 * (eta0 + 1.0))));
        }

        eta_stat_sigma = new LUDecomposition(eta_stat_sigma).getSolver().getInverse();
        for (int k = 0; k < number_of_topics; k++)
            u.m_SigmaP[k] = eta_stat_sigma.getData();//all topics share the same covariance
    }

    //variational inference for p(P|\nu,\Sigma) for each user
    private void update_nu(_User4ETBIR u){
        int idx = m_usersIndex.get(u.getUserID());
        ArrayList<Integer> Iu = new ArrayList<>();
        if(m_mapByUser.containsKey(idx))
            m_mapByUser.get(idx);

        double[][] etaMu = new double[number_of_topics][number_of_topics];
        double eta0;

        for (Integer itemIdx : Iu) {
            _Product4ETBIR item = (_Product4ETBIR) m_items.get(itemIdx);
            _Doc4ETBIR d = (_Doc4ETBIR) m_corpus.getCollection().get(m_reviewIndex.get(itemIdx + "_" + m_usersIndex.get(u.getUserID())));
            eta0 = Utils.sumOfArray(item.m_eta);
            for (int k = 0; k < number_of_topics; k++) {
                for(int l=0; l<number_of_topics; l++) {
                    etaMu[k][l] += d.m_mu[k] * item.m_eta[l] / eta0;
                }
            }
        }

        double[][] Sigma = u.m_SigmaP[0];
        for (int k = 0; k < number_of_topics; k++) {
            Utils.scaleArray(etaMu[k], m_rho);
            for(int l=0; l<number_of_topics; l++) {
                u.m_nuP[k][l] = 0;
                for(int j=0; j<number_of_topics; j++) {
                    u.m_nuP[k][l] += etaMu[k][j] * Sigma[l][j];
                    if(!m_flag_diagonal_lambda){
                        u.m_nuP[k][l] += m_sigma * m_lambda * Utils.sumOfArray(Sigma[l]);
                    }else {
                        if (j == k) {
                            u.m_nuP[k][l] += m_sigma * m_lambda * Sigma[l][j];
                        }
                    }
                }
            }
        }
    }

    // update eta with non-negative constraint using fix step graident descent
    private void update_eta(_Product4ETBIR i, double[] pNuStates, double[][] pSumStates){
        double fValue = 1.0, lastFValue, cvg = 1e-6, diff, iterMax = 20, iter = 0, alpha0 = Utils.sumOfArray(m_alpha);
        double stepsize = 1e-4;

        double[] etaG = new double[number_of_topics], etaH = new double[number_of_topics];
        double[] eta_log = new double[number_of_topics];

        for(int k = 0; k < number_of_topics; k++){
            eta_log[k] = Math.log(i.m_eta[k]);
            etaH[k] = 1.0;
        }

        boolean warning;
        do{
            warning = false;
            double eta0 = Utils.sumOfArray(i.m_eta);
            double diGammaEta0 = Utils.digamma(eta0);
            double triGammaEta0 = Utils.trigamma(eta0);

            lastFValue = fValue;
            fValue = -Utils.lgamma(eta0);

            for(int k = 0; k < number_of_topics; k++) {
                double gTerm2 = 0.0;
                double gTerm3 = pSumStates[k][k];
                double gTerm4 = 0.0;
                double term3 = pSumStates[k][k];

                for(int l = 0; l < number_of_topics; l++){
                    gTerm2 += pNuStates[l] * i.m_eta[l];
                    gTerm3 += 2 * pSumStates[l][k] * i.m_eta[l];
                    for(int p = 0; p < number_of_topics; p++)
                        gTerm4 += i.m_eta[l] * i.m_eta[p] * pSumStates[l][p];
                    gTerm4 += i.m_eta[l] * pSumStates[l][l];
                    term3 += i.m_eta[l] * pSumStates[l][k];
                }

                etaG[k] = Utils.trigamma(i.m_eta[k]) * i.m_eta[k] * (m_alpha[k] - i.m_eta[k])
                        - triGammaEta0 * i.m_eta[k] * (alpha0 - eta0)
                        + m_rho * i.m_eta[k] * pNuStates[k] / eta0
                        - m_rho * i.m_eta[k] * gTerm2 / (eta0 * eta0)
                        - m_rho * i.m_eta[k] * gTerm3 / (2 * eta0 * (eta0 + 1.0))
                        + m_rho * (2 * eta0 + 1.0) * i.m_eta[k] * gTerm4 / (2 * eta0 * eta0 * (eta0 + 1.0) * (eta0 + 1.0));

                fValue += (m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEta0)
                        + Utils.lgamma(i.m_eta[k])
                        + m_rho * i.m_eta[k] * pNuStates[k] / eta0
                        - m_rho * i.m_eta[k] * term3 / (2 * eta0 * (eta0 + 1.0));

                if(Double.isNaN(fValue) || Double.isInfinite(fValue)){
                    warning = true;
                    break;
                }
            }

            // fix stepsize
            for(int k = 0; k < number_of_topics; k++) {
                if(m_flag_gd)
                    eta_log[k] += stepsize * etaG[k];//gd
                else
                    eta_log[k] += stepsize/Math.sqrt(etaH[k]) * etaG[k];//ada gradient update
                i.m_eta[k] = Math.exp(eta_log[k]);
                etaH[k] += etaG[k] * etaG[k];
            }

            diff = (lastFValue - fValue) / lastFValue;
        }while(iter++ < iterMax && Math.abs(diff) > cvg && !warning);
    }

    @Override
    protected int getCorpusSize() {
        return m_mapByItem.size();//training item size
    }

    // calculate the likelihood of user-related terms (term2-term7)
    private double calc_log_likelihood_per_user(_User4ETBIR u){
        double log_likelihood = 0.0;

        for(int k = 0; k < number_of_topics; k++){
            double temp1 = 0.0;
            for(int l = 0; l < number_of_topics; l++)
                temp1 += u.m_SigmaP[k][l][l] + u.m_nuP[k][l] * u.m_nuP[k][l];
            if(!m_flag_diagonal_lambda)
                temp1 += m_lambda * m_lambda * number_of_topics - 2 * m_lambda * Utils.sumOfArray(u.m_nuP[k]);
            else
                temp1 += m_lambda * m_lambda - 2 * m_lambda * u.m_nuP[k][k];

            double det = new LUDecomposition(MatrixUtils.createRealMatrix(u.m_SigmaP[k])).getDeterminant();
            log_likelihood += -0.5 * (temp1 * m_sigma - number_of_topics)
                    + 0.5 * (number_of_topics * Math.log(m_sigma) + Math.log(det));
        }

//        if (Double.isNaN(log_likelihood)) {
//            System.err.format("[Warning]User %s likelihood encounters NaN!\n", u.getUserID());
//        } else if (Double.isInfinite(log_likelihood)) {
//            System.err.format("[Warning]User %s likelihood encounters infinity!\n", u.getUserID());
//        }

        return log_likelihood;
    }

    // calculate the likelihood of item-related terms (term1-term6)
    private double calc_log_likelihood_per_item(_Product4ETBIR i){
        double eta0 = Utils.sumOfArray(i.m_eta);
        double diGammaEtaSum = Utils.digamma(eta0);

        double log_likelihood = Utils.lgamma(Utils.sumOfArray(m_alpha)) - Utils.lgamma(eta0);
        for(int k = 0; k < number_of_topics; k++){
            log_likelihood += (m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEtaSum);
            log_likelihood -= Utils.lgamma(m_alpha[k]) - Utils.lgamma(i.m_eta[k]);
        }

//        if (Double.isNaN(log_likelihood)) {
//            System.err.format("[Warning]Item %s likelihood encounters NaN!\n", i.getID());
//        } else if (Double.isInfinite(log_likelihood)) {
//            System.err.format("[Warning]Item %s likelihood encounters infinite!\n", i.getID());
//        }

        return log_likelihood;
    }

    // calculate the likelihood of doc-related terms (term3-term8 + term4-term9 + term5)
    private double calc_log_likelihood_per_doc(_Doc4ETBIR doc, _User4ETBIR currentU, _Product4ETBIR currentI) {
        double log_likelihood = 0.5 * number_of_topics * (Math.log(m_rho) + 1)  - doc.getDocLength() * doc.m_logZeta;
        double eta0 = Utils.sumOfArray(currentI.m_eta);

        //likelihood from topic proportion
        double term1 = 0.0, term2 = 0.0, term3 = 0.0, term4 = 0.0;
        for(int k = 0; k < number_of_topics; k++){
            term1 += doc.m_Sigma[k] + doc.m_mu[k] * doc.m_mu[k];
            for(int j = 0; j < number_of_topics; j++){
                term2 += currentI.m_eta[k] * currentU.m_nuP[j][k] * doc.m_mu[j];

                for(int l = 0; l < number_of_topics; l++)
                    term3 += currentI.m_eta[j] * currentI.m_eta[l] *
                            (currentU.m_SigmaP[k][j][l] + currentU.m_nuP[k][j] * currentU.m_nuP[k][l]);
                term3 += currentI.m_eta[j] * (currentU.m_SigmaP[k][j][j] + currentU.m_nuP[k][j] * currentU.m_nuP[k][j]);
            }
            term4 += Math.log(doc.m_Sigma[k]);
        }
        log_likelihood += -m_rho * (0.5 * term1 - term2 / eta0 + term3 / (2 * eta0 * (eta0 + 1.0)))
                + 0.5 * term4;

        //likelihood from the words
        int wid;
        double v;
        _SparseFeature[] fv = doc.getSparse();
        for(int k = 0; k < number_of_topics; k++) {
            for (int n = 0; n < fv.length; n++) {
                wid = fv[n].getIndex();
                v = fv[n].getValue() * doc.m_phi[n][k];
                log_likelihood += v * (doc.m_mu[k] - Math.log(doc.m_phi[n][k]) + topic_term_probabilty[k][wid]);
            }
        }

//        if (Double.isNaN(log_likelihood)) {
//            System.err.format("[Warning]Encounter likelihood NaN in document %s for item %s!\n", doc.getID(), currentI.getID());
//        } else if (Double.isInfinite(log_likelihood)) {
//            System.err.format("[Warning]Encounter likelihood infinity in document %s for item %s!\n", doc.getID(), currentI.getID());
//        }

        return log_likelihood;
    }

    @Override
    protected void initialize_probability(Collection<_Doc> docs) {
        System.out.println("[Info]Initializing documents...");
        for(_Doc doc : m_corpus.getCollection())
            ((_Doc4ETBIR) doc).setTopics4Variational(number_of_topics, d_alpha, d_mu, d_sigma_theta);

        System.out.println("[Info]Initializing users...");
        for(_User u : m_users){
            _User4ETBIR user = (_User4ETBIR) u;
            user.setTopics4Variational(number_of_topics, d_nu, d_sigma_P);
        }

        System.out.println("[Info]Initializing items...");
        for(_Product i : m_items) {
            _Product4ETBIR item = (_Product4ETBIR) i;
            item.setTopics4Variational(number_of_topics, d_alpha);
        }

        // initialize with all smoothing terms
        init();
        Arrays.fill(m_alpha, d_alpha);

        System.out.println("[Info]Initializing ETBIR model...");
        // initialize topic-word allocation, p(w|z)
        for(_Doc doc:docs)
            updateStats4Doc((_Doc4ETBIR) doc);

        for(int u_idx:m_mapByUser.keySet()) {
            _User4ETBIR user = (_User4ETBIR) m_users.get(u_idx);
            updateStats4User(user);
        }

        for(int i_idx : m_mapByItem.keySet()){
            _Product4ETBIR item = (_Product4ETBIR) m_items.get(i_idx);
            updateStats4Item(item);
        }

        calculate_M_step(0);
    }

    public void analyzeCorpus(){
        m_bipartite = new BipartiteAnalyzer(m_corpus);
        m_bipartite.analyzeCorpus();
        m_users = m_bipartite.getUsers();
        m_items = m_bipartite.getItems();
        m_usersIndex = m_bipartite.getUsersIndex();
        m_itemsIndex = m_bipartite.getItemsIndex();
        m_reviewIndex = m_bipartite.getReviewIndex();
    }

    @Override
    public void EMonCorpus() {
        m_trainSet = m_corpus.getCollection();
        //analyze corpus and generate bipartite
        analyzeCorpus();

        m_bipartite.analyzeBipartite(m_trainSet, "train");
        m_mapByUser = m_bipartite.getMapByUser();
        m_mapByItem = m_bipartite.getMapByItem();

        EM();
    }

    @Override
    public boolean EM(){
        System.out.format("%s\n", toString());
        initialize_probability(m_trainSet);

        m_collectCorpusStats = true;
        int iter = 1;
        double lastAllLikelihood = 1.0;
        double currentAllLikelihood;
        double converge = 0.0;

        boolean warning;
        do{
            warning=false;
            System.out.format("====================\n[Info]Start EM iteration %d....\n", iter);
            if(m_multithread)
                currentAllLikelihood = multithread_E_step();
            else
                currentAllLikelihood = E_step();

            if(Double.isNaN(currentAllLikelihood) || Double.isInfinite(currentAllLikelihood)){
                System.err.println("[Error]E_step produces NaN likelihood...");
                warning = true;
            }

            if(iter > 0)
                converge = Math.abs((lastAllLikelihood - currentAllLikelihood) / lastAllLikelihood);
            else
                converge = 1.0;

            System.out.format("[Info]M-step %d....\n--------------------------\n", iter);
            calculate_M_step(iter);

            lastAllLikelihood = currentAllLikelihood;
            System.out.format("[Info]EM iteration %d: likelihood is %.2f, converges to %.8f...\n\n",
                    iter, currentAllLikelihood, converge);

//            printTopWords(10);//print out the top words every iteration
        }while(++iter < number_of_iteration && converge > m_converge && !warning);

        return !warning;
    }

    @Override
    public void calculate_M_step(int iter) {
        super.calculate_M_step(iter);

        if(!m_flag_fix_lambda)
            m_lambda = m_lambda_Stats / (m_mapByUser.size() * number_of_topics);
//        m_rho = m_trainSet.size() * number_of_topics / (m_thetaStats + m_eta_p_Stats - 2 * m_eta_mean_Stats); //maximize likelihood for \rho of p(\theta|P\gamma, \rho)
//        m_sigma = m_mapByUser.size() * number_of_topics * number_of_topics / m_pStats; //maximize likelihood for \sigma

        // update per-document topic distribution vectors
        finalEst();
    }

    @Override
    public double[] oneFoldValidation(){
        analyzeCorpus();
        m_trainSet = new ArrayList<_Doc>();
        m_testSet = new ArrayList<_Doc>();
        for(_Doc d:m_corpus.getCollection()){
            if(d.getType() == _Doc.rType.TRAIN){
                m_trainSet.add(d);
            }else if(d.getType() == _Doc.rType.TEST){
                m_testSet.add(d);
            }
        }

        System.out.format("train size = %d, test size = %d....\n", m_trainSet.size(), m_testSet.size());

        long start = System.currentTimeMillis();
        //train
        m_bipartite.analyzeBipartite(m_trainSet, "train");
        m_mapByUser = m_bipartite.getMapByUser();
        m_mapByItem = m_bipartite.getMapByItem();
        boolean success = EM();

        //test
        m_bipartite.analyzeBipartite(m_testSet, "test");
        m_mapByUser_test = m_bipartite.getMapByUser_test();
        m_mapByItem_test = m_bipartite.getMapByItem_test();

        double[] results = new double[2];
        if(success) {
            results = Evaluation2();
            System.out.format("[Info]%s Train/Test SUCCEED in %.2f seconds...\n", this.toString(), (System.currentTimeMillis() - start) / 1000.0);
        }else{
            Arrays.fill(results, Double.NaN);
            System.err.format("[Error]%s Train/Test FAIL in %.2f seconds...\n", this.toString(), (System.currentTimeMillis() - start) / 1000.0);
        }

        return results;
    }

    //k-fold Cross Validation.
    @Override
    public void crossValidation(int k) {
        analyzeCorpus();
        m_trainSet = new ArrayList<_Doc>();
        m_testSet = new ArrayList<_Doc>();

        double[] perf = new double[k];
        double[] like = new double[k];
        System.out.println("[Info]Start RANDOM cross validation...");
        if(m_randomFold){
            m_corpus.shuffle(k);
            int[] masks = m_corpus.getMasks();
            ArrayList<_Doc> docs = m_corpus.getCollection();
            //Use this loop to iterate all the ten folders, set the train set and test set.
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < masks.length; j++) {
                    if( masks[j]==i )
                        m_testSet.add(docs.get(j));
                    else
                        m_trainSet.add(docs.get(j));
                }

                System.out.format("====================\n[Info]Fold No. %d: train size = %d, test size = %d....\n", i, m_trainSet.size(), m_testSet.size());

                long start = System.currentTimeMillis();
                //train
                m_bipartite.analyzeBipartite(m_trainSet, "train");
                m_mapByUser = m_bipartite.getMapByUser();
                m_mapByItem = m_bipartite.getMapByItem();
                EM();

                //test
                m_bipartite.analyzeBipartite(m_testSet, "test");
                m_mapByUser_test = m_bipartite.getMapByUser_test();
                m_mapByItem_test = m_bipartite.getMapByItem_test();
                double[] results = Evaluation2();
                perf[i] = results[0];
                like[i] = results[1];

                System.out.format("[Info]%s Train/Test finished in %.2f seconds...\n", this.toString(), (System.currentTimeMillis()-start)/1000.0);
                m_trainSet.clear();
                m_testSet.clear();
            }
        }

        //output the performance statistics
        double mean = Utils.sumOfArray(perf)/k, var = 0;
        for(int i=0; i<perf.length; i++)
            var += (perf[i]-mean) * (perf[i]-mean);
        var = Math.sqrt(var/k);
        System.out.format("[Stat]Perplexity %.3f+/-%.3f\n", mean, var);

        mean = Utils.sumOfArray(like)/k;
        var = 0;
        for(int i=0; i<like.length; i++)
            var += (like[i]-mean) * (like[i]-mean);
        var = Math.sqrt(var/k);
        System.out.format("[Stat]Loglikelihood %.3f+/-%.3f\n", mean, var);
    }

    private int getTotalLength(){
        int length = 0;
        for(_Doc d:m_testSet)
            length += d.getTotalDocLength();
        return length;
    }

    @Override
    public double[] Evaluation2() {
        m_collectCorpusStats = false;
        double loglikelihood;
        double totalWords = 0.0;

        //coldstart_all, coldstart_user, coldstart_item, normal;
        m_likelihood_array = new double[5];
        m_perplexity_array = new double[5];
        m_totalWords_array = new double[5];
        m_docSize_array = new double[5];

        if (m_multithread) {
            System.out.println("[Info]Start evaluation in thread");
            multithread_inference();//split the perplexity in this function
        } else {//did not split yet. deprecated
            System.out.println("[Info]Start evaluation in Normal");

            int iter=0;
            double last = -1.0, converge = 0.0;
            do {
                init();
                loglikelihood = 0.0;
                Arrays.fill(m_likelihood_array, 0);
                Arrays.fill(m_perplexity_array, 0);
                Arrays.fill(m_totalWords_array,0);
                Arrays.fill(m_docSize_array, 0);

                for (_Doc d : m_testSet) {
                    loglikelihood += inference(d);
                }

                for (int u_idx : m_mapByUser_test.keySet()) {
                    _User4ETBIR user = (_User4ETBIR) m_users.get(u_idx);
                    loglikelihood += varInference4User(user);
                }

                for (int i_idx : m_mapByItem_test.keySet()) {
                    _Product4ETBIR item = (_Product4ETBIR) m_items.get(i_idx);
                    loglikelihood += varInference4Item(item);
                }

                if(iter > 0)
                    converge = Math.abs((loglikelihood - last) / last);
                else
                    converge = 1.0;

                last = loglikelihood;
                if(converge < m_varConverge)
                    break;
                System.out.print("---likelihood: " + last + "\n");
            }while(iter++<m_varMaxIter);
        }
        System.out.format("[Stat]Test evaluation finished: %d docs\n", m_testSet.size());

        //0,1: perplexity_coldstart_all, likelihood_coldstart_all
        //2,3: perplexity_coldstart_user, likelihood_coldstart_user
        //4,5: perplexity_coldstart_item, likelihood_coldstart_item
        //6,7: perplexity_normal, likelihood_normal
        //8,9: perplexity, likelihood
        double[] results = new double[10];
        //coldstart_all, coldstart_user, coldstart_item, normal;
        for(int i = 0; i < 5; i++){
            if(m_totalWords_array[i] > 0){
                results[2*i] = Math.exp(-m_perplexity_array[i] /m_totalWords_array[i]);
                results[2*i+1] = m_likelihood_array[i] / m_docSize_array[i];
            }
            System.out.format("[Stat]%d part has %f docs: perplexity is %.3f and log-likelihood is %.3f\n", i, m_docSize_array[i], results[2*i], results[2*i+1]);
        }
        return results;
    }

    @Override
    public void printAggreTopWords(int k, String topWordPath, HashMap<String, List<_Doc>> docCluster) {
        File file = new File(topWordPath);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }

        try{
            PrintWriter topWordWriter = new PrintWriter(file);

            for(Map.Entry<String, List<_Doc>> entryU : docCluster.entrySet()) {
                double[] gamma = new double[number_of_topics];

                for(_Doc d:entryU.getValue()) {
                    _Doc4ETBIR doc = (_Doc4ETBIR) d;
                    double expSum = Utils.logSum(doc.m_mu);
                    for (int i = 0; i < number_of_topics; i++)
                        gamma[i] += Math.exp(doc.m_mu[i]-expSum);
                }
                for(int i = 0; i < number_of_topics; i++)
                    gamma[i] /= entryU.getValue().size();

                topWordWriter.format("UserID %s(%d reviews)\n", entryU.getKey(), entryU.getValue().size());
                for (int i = 0; i < topic_term_probabilty.length; i++) {
                    MyPriorityQueue<_RankItem> fVector = new MyPriorityQueue<_RankItem>(k);
                    for (int j = 0; j < vocabulary_size; j++)
                        fVector.add(new _RankItem(m_corpus.getFeature(j), topic_term_probabilty[i][j]));

                    topWordWriter.format("-- Topic %d(%.5f):\t", i, gamma[i]);
                    for (_RankItem it : fVector) {
                        topWordWriter.format("%s(%.5f)\t", it.m_name, m_logSpace ? Math.exp(it.m_value) : it.m_value);
                    }
                    topWordWriter.write("\n");
                }
            }
            topWordWriter.close();
        } catch(Exception ex){
            System.err.format("[Error]Failed to open file %s\n", topWordPath);
        }

    }

    @Override
    public void printParam(String folderName, String topicmodel){
        String alphaFile = String.format("%s%s_priorAlpha_%d.txt", folderName, topicmodel, number_of_topics);
        String sigmaFile = String.format("%s%s_priorSigma_%d.txt", folderName, topicmodel, number_of_topics);
        String lambdaFile = String.format("%s%s_priorLambda_%d.txt", folderName, topicmodel, number_of_topics);
        String rhoFile = String.format("%s%s_priorRho_%d.txt", folderName, topicmodel, number_of_topics);

        String etaFile = String.format("%s%s_postEta_%d.txt", folderName, topicmodel, number_of_topics);
        String nuFile = String.format("%s%s_postNu_%d.txt", folderName, topicmodel, number_of_topics);

        String softmaxFile = String.format("%s%s_postSoftmax_%d.txt", folderName, topicmodel, number_of_topics);

        //print out prior parameter of dirichlet: alpha
        File file = new File(alphaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter alphaWriter = new PrintWriter(file);
            for (int i = 0; i < number_of_topics; i++)
                alphaWriter.format("%.5f\t", this.m_alpha[i]);
            alphaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("[Error]Failed to open file %s\n", alphaFile);
        }

        //print out prior parameter of normal distribution for user matrix P: sigma and lambda
        file = new File(sigmaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        File file2 = new File(lambdaFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter sigmaWriter = new PrintWriter(file);
            PrintWriter lambdaWriter = new PrintWriter(file2);

            for(int i = 0;i < number_of_topics; i++) {
                lambdaWriter.format("%.5f\t", this.m_lambda);
                for(int j = 0; j < number_of_topics; j++) {
                    if(j == i) {
                        sigmaWriter.format("%.5f\t", this.m_sigma);
                    }else{
                        sigmaWriter.format("%.5f\t", (double) 0);
                    }
                }
            }
            sigmaWriter.close();
            lambdaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("[Error]Failed to open file %s or %s\n", sigmaFile, lambdaFile);
        }

        //print out prior parameter of normal distribution for doc: rho
        file = new File(rhoFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter rhoWriter = new PrintWriter(file);

            for(int i = 0;i < number_of_topics; i++) {
                for(int j = 0; j < number_of_topics; j++) {
                    if(j == i) {
                        rhoWriter.format("%.5f\t", this.m_rho);
                    }else{
                        rhoWriter.format("%.5f\t", (double) 0);
                    }
                }
            }
            rhoWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("[Error]Failed to open file %s\n", rhoFile);
        }

        //print out post parameter of dirichlet distribution for item: eta
        file = new File(etaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter etaWriter = new PrintWriter(file);

            for(int idx = 0; idx < m_items.size(); idx++) {
                etaWriter.write(String.format("item %d %s *********************\n", idx, m_items.get(idx).getID()));
                _Product4ETBIR item = (_Product4ETBIR) m_items.get(idx);
                for (int i = 0; i < number_of_topics; i++)
                    etaWriter.format("%.5f\t", item.m_eta[i]);
                etaWriter.write("\n");
            }
            etaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("[Error]Failed to open file %s\n", etaFile);
        }

        //print out post parameter of normal distribution for user matrix: nu and sigmaP
        file = new File(nuFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter nuWriter = new PrintWriter(file);
            for(int idx = 0; idx < m_users.size(); idx++) {
                nuWriter.write(String.format("No. %d UserID %s *********************\n", idx, m_users.get(idx).getUserID()));
                _User4ETBIR user = (_User4ETBIR) m_users.get(idx);
                for (int i = 0; i < number_of_topics; i++) {
                    nuWriter.format("-- %d column:\n", i);
                    for(int j = 0; j < number_of_topics; j++) {
                        nuWriter.format("%.5f\t", user.m_nuP[i][j]);
                    }
                    nuWriter.write("\n");
                }
            }
            nuWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("[Error]Failed to open file %s\n", nuFile);
        }

        file2 = new File(softmaxFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter softmaxWriter = new PrintWriter(file2);

            for(int idx = 0; idx < m_trainSet.size(); idx++) {
                _Doc4ETBIR doc = (_Doc4ETBIR) m_trainSet.get(idx);
                String userID = doc.getUserID();
                String itemID = doc.getItemID();
                _User4ETBIR user = (_User4ETBIR) m_users.get(m_usersIndex.get(userID));
                _Product4ETBIR item = (_Product4ETBIR) m_items.get(m_itemsIndex.get(itemID));

                softmaxWriter.write(String.format("No. %d Doc(user: %s, item: %s) ***************\n", idx,
                        userID, itemID));

                double[] inner = new double[number_of_topics];
                for (int i = 0; i < number_of_topics; i++) {
                    inner[i] = Utils.dotProduct(user.m_nuP[i], item.m_eta);
                }

                double sum = Utils.logSum(inner);
                for(int i = 0; i < number_of_topics; i++)
                    softmaxWriter.format("%.5f\t", Math.exp(inner[i] - sum));
                softmaxWriter.println();
            }
            softmaxWriter.close();
        } catch(Exception ex){
            System.err.format("[Error]Failed to open file %s\n", softmaxFile);
        }
    }

    public void printAggreTopWords2(int k, String topWordPath, HashMap<String, List<_Doc>> docCluster) {
        File file = new File(topWordPath);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        File file2 = new File(topWordPath.replace(".txt", "_est.txt"));
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter topWordWriter = new PrintWriter(file);
            PrintWriter topWordWriter2 = new PrintWriter(file2);

            for(Map.Entry<String, List<_Doc>> entryU : docCluster.entrySet()) {
                double[] gamma = new double[number_of_topics];
                double[] gamma_est = new double[number_of_topics];

                for(_Doc d:entryU.getValue()) {
                    _Doc4ETBIR doc = (_Doc4ETBIR) d;
                    double expSum = Utils.logSum(doc.m_mu);
                    for (int i = 0; i < number_of_topics; i++)
                        gamma[i] += Math.exp(doc.m_mu[i]-expSum);

                    String userName = doc.getUserID();
                    String itemName = doc.getItemID();
                    _User4ETBIR user = (_User4ETBIR) m_users.get(m_usersIndex.get(userName));
                    _Product4ETBIR item = (_Product4ETBIR) m_items.get(m_itemsIndex.get(itemName));
                    double[] theta = new double[number_of_topics];
                    for(int i = 0; i < number_of_topics; i++)
                        theta[i] = Utils.dotProduct(user.m_nuP[i], item.m_eta);
                    expSum = Utils.logSum(theta);
                    for(int i = 0; i < number_of_topics; i++)
                        gamma_est[i] += Math.exp(theta[i]-expSum);

                }
                for(int i = 0; i < number_of_topics; i++){
                    gamma[i] /= entryU.getValue().size();
                    gamma_est[i] /= entryU.getValue().size();
                }

                topWordWriter.format("UserID %s(%d reviews)\n", entryU.getKey(), entryU.getValue().size());
                topWordWriter2.format("UserID %s(%d reviews)\n", entryU.getKey(), entryU.getValue().size());
                for (int i = 0; i < topic_term_probabilty.length; i++) {
                    MyPriorityQueue<_RankItem> fVector = new MyPriorityQueue<_RankItem>(k);
                    for (int j = 0; j < vocabulary_size; j++)
                        fVector.add(new _RankItem(m_corpus.getFeature(j), topic_term_probabilty[i][j]));

                    topWordWriter.format("-- Topic %d(%.5f):\t", i, gamma[i]);
                    topWordWriter2.format("-- Topic %d(%.5f):\t", i, gamma_est[i]);
                    for (_RankItem it : fVector) {
                        topWordWriter.format("%s(%.5f)\t", it.m_name, m_logSpace ? Math.exp(it.m_value) : it.m_value);
                        topWordWriter2.format("%s(%.5f)\t", it.m_name, m_logSpace ? Math.exp(it.m_value) : it.m_value);
                    }
                    topWordWriter.write("\n");
                    topWordWriter2.write("\n");
                }
            }
            topWordWriter.close();
            topWordWriter2.close();
        } catch(Exception ex){
            System.err.println("File Not Found: " + topWordPath);
        }

    }

    public void printParam2(String folderName, String topicmodel){
        String alphaFile = folderName + topicmodel + "_priorAlpha.txt";
        String sigmaFile = folderName + topicmodel + "_priorSigma.txt";
        String lambdaFile = folderName + topicmodel + "_priorLambda.txt";
        String rhoFile = folderName + topicmodel + "_priorRho.txt";

        String etaFile = folderName + topicmodel + "_postEta.txt";
        String nuFile = folderName + topicmodel + "_postNu.txt";
        String sigmaPFile = folderName + topicmodel + "_postSigmaP.txt";
        String muFile = folderName + topicmodel + "_postMu.txt";
        String sigmaThetaFile = folderName + topicmodel + "_postSigmaTheta.txt";

        String innerFile = folderName + topicmodel + "_postInnerProduct.txt";
        String softmaxFile = folderName + topicmodel + "_postSoftmax.txt";

        //print out prior parameter of dirichlet: alpha
        File file = new File(alphaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter alphaWriter = new PrintWriter(file);
            for (int i = 0; i < number_of_topics; i++)
                alphaWriter.format("%.5f\t", this.m_alpha[i]);
            alphaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s Not Found", alphaFile);
        }

        //print out prior parameter of normal distribution for user matrix P: sigma and lambda
        file = new File(sigmaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        File file2 = new File(lambdaFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter sigmaWriter = new PrintWriter(file);
            PrintWriter lambdaWriter = new PrintWriter(file2);

            for(int i = 0;i < number_of_topics; i++) {
                lambdaWriter.format("%.5f\t", this.m_lambda);
                for(int j = 0; j < number_of_topics; j++) {
                    if(j == i) {
                        sigmaWriter.format("%.5f\t", this.m_sigma);
                    }else{
                        sigmaWriter.format("%.5f\t", (double) 0);
                    }
                }
            }
            sigmaWriter.close();
            lambdaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s or %s Not Found", sigmaFile, lambdaFile);
        }

        //print out prior parameter of normal distribution for doc: rho
        file = new File(rhoFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter rhoWriter = new PrintWriter(file);

            for(int i = 0;i < number_of_topics; i++) {
                for(int j = 0; j < number_of_topics; j++) {
                    if(j == i) {
                        rhoWriter.format("%.5f\t", this.m_rho);
                    }else{
                        rhoWriter.format("%.5f\t", (double) 0);
                    }
                }
            }
            rhoWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s Not Found", rhoFile);
        }

        //print out post parameter of dirichlet distribution for item: eta
        file = new File(etaFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter etaWriter = new PrintWriter(file);

            for(int idx = 0; idx < m_items.size(); idx++) {
                etaWriter.write(String.format("item %d %s *********************\n", idx, m_items.get(idx).getID()));
                _Product4ETBIR item = (_Product4ETBIR) m_items.get(idx);
                for (int i = 0; i < number_of_topics; i++)
                    etaWriter.format("%.5f\t", item.m_eta[i]);
                etaWriter.write("\n");
            }
            etaWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s Not Found", etaFile);
        }

        //print out post parameter of normal distribution for user matrix: nu and sigmaP
        file = new File(nuFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        file2 = new File(sigmaPFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter nuWriter = new PrintWriter(file);
            PrintWriter sigmaPWriter = new PrintWriter(file2);

            for(int idx = 0; idx < m_users.size(); idx++) {
                nuWriter.write(String.format("No. %d UserID %s *********************\n", idx, m_users.get(idx).getUserID()));
                sigmaPWriter.write(String.format("No. %d UserID %s *********************\n", idx, m_users.get(idx).getUserID()));
                _User4ETBIR user = (_User4ETBIR) m_users.get(idx);
                for (int i = 0; i < number_of_topics; i++) {
                    sigmaPWriter.format("-- %d column:\n", i);
                    nuWriter.format("-- %d column:\n", i);
                    for(int j = 0; j < number_of_topics; j++) {
                        nuWriter.format("%.5f\t", user.m_nuP[i][j]);
                        for(int k = 0; k < number_of_topics; k++)
                            sigmaPWriter.format("%.5f\t", user.m_SigmaP[i][j][k]);
                        sigmaPWriter.println();
                    }
                    nuWriter.write("\n");
                }
            }
            nuWriter.close();
            sigmaPWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s or %s Not Found", nuFile, sigmaPFile);
        }

        //print out posterior parameter of normal distribution for each document: mu and sigmaTheta
        file = new File(muFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        file2 = new File(sigmaThetaFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter muWriter = new PrintWriter(file);
            PrintWriter sigmaThetaWriter = new PrintWriter(file2);

            for(int idx = 0; idx < m_trainSet.size(); idx++) {
                muWriter.write(String.format("No. %d Doc(user: %s, item: %s) ***************\n", idx,
                        ((_Doc4ETBIR) m_trainSet.get(idx)).getUserID(),
                        ((_Doc4ETBIR) m_trainSet.get(idx)).getItemID()));
                sigmaThetaWriter.write(String.format("No. %d Doc(user: %s, item: %s) ***************\n", idx,
                        ((_Doc4ETBIR) m_trainSet.get(idx)).getUserID(),
                        ((_Doc4ETBIR) m_trainSet.get(idx)).getItemID()));
                for (int i = 0; i < number_of_topics; i++) {
                    muWriter.format("%.5f\t", ((_Doc4ETBIR) m_trainSet.get(idx)).m_mu[i]);
                    sigmaThetaWriter.format("%.5f\t", ((_Doc4ETBIR) m_trainSet.get(idx)).m_Sigma[i]);
                }
                muWriter.println();
                sigmaThetaWriter.println();
            }
            muWriter.close();
        } catch(FileNotFoundException ex){
            System.err.format("File %s or %s Not Found", muFile, sigmaThetaFile);
        }

        //print out estimated parameter of normal for doc: the inner product P\gamma and its softmax
        file = new File(innerFile);
        try{
            file.getParentFile().mkdirs();
            file.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        file2 = new File(softmaxFile);
        try{
            file2.getParentFile().mkdirs();
            file2.createNewFile();
        } catch(IOException e){
            e.printStackTrace();
        }
        try{
            PrintWriter innerWriter = new PrintWriter(file);
            PrintWriter softmaxWriter = new PrintWriter(file2);

            for(int idx = 0; idx < m_trainSet.size(); idx++) {
                _Doc4ETBIR doc = (_Doc4ETBIR) m_trainSet.get(idx);
                String userID = doc.getUserID();
                String itemID = doc.getItemID();
                _User4ETBIR user = (_User4ETBIR) m_users.get(m_usersIndex.get(userID));
                _Product4ETBIR item = (_Product4ETBIR) m_items.get(m_itemsIndex.get(itemID));

                innerWriter.write(String.format("No. %d Doc(user: %s, item: %s) ***************\n", idx,
                        userID, itemID));
                softmaxWriter.write(String.format("No. %d Doc(user: %s, item: %s) ***************\n", idx,
                        userID, itemID));

                double[] inner = new double[number_of_topics];
                for (int i = 0; i < number_of_topics; i++) {
                    inner[i] = Utils.dotProduct(user.m_nuP[i], item.m_eta);
                    innerWriter.format("%.5f\t", inner[i]);
                }
                innerWriter.println();

                double sum = Utils.logSum(inner);
                for(int i = 0; i < number_of_topics; i++)
                    softmaxWriter.format("%.5f\t", Math.exp(inner[i] - sum));
                softmaxWriter.println();
            }
            innerWriter.close();
            softmaxWriter.close();
        } catch(Exception ex){
            System.err.format("File %s or %s Not Found", innerFile, softmaxFile);
        }
    }

    @Override
    public HashMap<String, List<_Doc>> getDocByUser(){
        HashMap<String, List<_Doc>> docByUser = new HashMap<>();
        for(Integer uIdx : m_mapByUser.keySet()) {
            String userName = m_users.get(uIdx).getUserID();
            List<_Doc> docs = new ArrayList<>();
            for(Integer iIdx : m_mapByUser.get(uIdx)){
                docs.add(m_corpus.getCollection().get(m_reviewIndex.get(iIdx + "_" + uIdx)));
            }
            docByUser.put(userName, docs);
        }
        return docByUser;
    }

    @Override
    public HashMap<String, List<_Doc>> getDocByItem(){
        HashMap<String, List<_Doc>> docByItem = new HashMap<>();
        for(Integer iIdx : m_mapByItem.keySet()) {
            String itemName = m_items.get(iIdx).getID();
            List<_Doc> docs = new ArrayList<>();
            for(Integer uIdx : m_mapByItem.get(iIdx)){
                docs.add(m_corpus.getCollection().get(m_reviewIndex.get(iIdx + "_" + uIdx)));
            }
            docByItem.put(itemName, docs);
        }
        return docByItem;
    }

    public void loadParam(String filename){
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filename), "UTF-8"));
            String line;

            String itemID = "";
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                if(line.startsWith("ID")){
                    itemID = line.split("\\(")[0].split(" ")[1];
                    idx = 0;
                }else if(line.startsWith("-")){
                    String firststr = line.split("\\:")[0];
                    String secondstr = firststr.split("\\(")[1];
                    String thirdstr = secondstr.split("\\)")[0];
                    ((_Product4ETBIR)m_items.get(m_itemsIndex.get(itemID))).m_eta[idx++] = Double.parseDouble(
                            thirdstr);
                }
            }
            reader.close();
            System.out.format("Loading eta from %s\n", filename);
        } catch(IOException e){
            System.err.format("[Error]Failed to open file %s!!", filename);
        }
    }
}