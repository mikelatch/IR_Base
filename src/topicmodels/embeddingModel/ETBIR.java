package topicmodels.embeddingModel;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import structures.*;
import topicmodels.LDA.LDA_Variational;
import utils.Utils;

/**
 * @author Lu Lin
 * Variational inference for Explainable Topic-Based Item Recommendation (ETBIR) model
 */
public class ETBIR extends LDA_Variational {

    //variables defined by base class
//    protected int number_of_topics;
//    protected double m_converge;//relative change in log-likelihood to terminate EM
//    protected int vocabulary_size;
//    protected int number_of_iteration;//number of iterations in inferencing testing document
//    protected _Corpus m_corpus;
//    protected double d_beta;
//    protected double d_alpha; // smoothing of p(z|d)
//    protected double[][] topic_term_probabilty ; /* p(w|z) */
//
//    protected int m_varMaxIter;
//    protected double m_varConverge;
//    protected double[] m_alpha; // we can estimate a vector of alphas as in p(\theta|\alpha)
//    protected double[] m_alphaStat; // statistics for alpha estimation

    protected double d_rho;
    protected double d_sigma;

    protected int number_of_users;
    protected int number_of_items;

    protected _User4ETBIR[] m_users;
    protected _Product4ETBIR[] m_items;

    protected HashMap<String, Integer> m_usersIndex; //(userID, index in m_users)
    protected HashMap<String, Integer> m_itemsIndex; //(itemID, index in m_items)

    protected HashMap<Integer,ArrayList<Integer>>  m_mapByUser; //adjacent list for user
    protected HashMap<Integer, ArrayList<Integer>> m_mapByItem;
    protected HashMap<String, Integer> m_reviewIndex; //(itemIndex_userIndex, index in m_corpus.m_collection)

    protected double m_rho;
    protected double m_sigma;
    protected double[][] m_beta; //topic_term_probability

    double[] m_alphaG; // gradient for alpha
    double[] m_alphaH; // Hessian for alpha
    double[] m_etaStats;
    double m_pStats;
    double m_thetaStats;
    double m_eta_p_Stats;
    double m_eta_mean_Stats;

    public ETBIR(int emMaxIter, double emConverge,
                 double beta, _Corpus corpus, double lambda,
                 int number_of_topics, double alpha, int varMaxIter, double varConverge, //LDA_variational
                 double sigma, double rho) {
        super( emMaxIter,  emConverge, beta,  corpus,  lambda, number_of_topics,
         alpha,  varMaxIter,  varConverge);

        this.d_sigma = sigma;
        this.d_rho = rho;
    }

    public void loadCorpus(){
        System.out.println("Loading data to model...");

        m_usersIndex = new HashMap<String, Integer>();
        m_itemsIndex = new HashMap<String, Integer>();
        m_reviewIndex = new HashMap<String, Integer>();
        m_mapByUser = new HashMap<Integer, ArrayList<Integer>>();
        m_mapByItem = new HashMap<Integer, ArrayList<Integer>>();

        int u_index = -1, i_index = -1;
        for(int d = 0; d < m_corpus.getCollection().size(); d++){
            _Doc doc = m_corpus.getCollection().get(d);
            String userID = doc.getTitle();
            String itemID = doc.getItemID();

            if(!m_usersIndex.containsKey(userID)){
                m_usersIndex.put(userID, ++u_index);
                m_mapByUser.put(u_index, new ArrayList<Integer>());
            }

            if(!m_itemsIndex.containsKey(itemID)){
                m_itemsIndex.put(itemID, ++i_index);
                m_mapByItem.put(i_index, new ArrayList<Integer>());
            }

            int uIdx = m_usersIndex.get(userID);
            int iIdx = m_itemsIndex.get(itemID);
            m_mapByUser.get(uIdx).add(iIdx);
            m_mapByItem.get(iIdx).add(uIdx);

            m_reviewIndex.put(iIdx + "_" + uIdx, d);
        }

        m_users = new _User4ETBIR[m_usersIndex.size()];
        for(Map.Entry<String, Integer> entry: m_usersIndex.entrySet()){
            m_users[entry.getValue()] = new _User4ETBIR(entry.getKey());
        }

        m_items = new _Product4ETBIR[m_itemsIndex.size()];
        for(Map.Entry<String, Integer> entry: m_itemsIndex.entrySet()){
            m_items[entry.getValue()] = new _Product4ETBIR(entry.getKey());
        }

        this.number_of_items = m_mapByItem.size();
        this.number_of_users = m_mapByUser.size();
        this.vocabulary_size = m_corpus.getFeatureSize();

        System.out.println("-- vocabulary size: " + vocabulary_size);
        System.out.println("-- corpus size: " + m_reviewIndex.size());
        System.out.println("-- item number: " + number_of_items);
        System.out.println("-- user number: " + number_of_users);
    }

    //create space; initial parameters
    public void initModel(){
        this.m_alpha = new double[number_of_topics];
        this.m_beta = new double[number_of_topics][vocabulary_size];
        this.m_alphaG = new double[number_of_topics];
        this.m_alphaH = new double[number_of_topics];

        //initialize parameters
        Random r = new Random();
        m_rho = d_rho;
        m_sigma = d_sigma;
        Arrays.fill(m_alpha, d_alpha);
        double val = 0.0;
        for(int k = 0; k < number_of_topics; k++){
            double sum = 0.0;
            for(int v = 0; v < vocabulary_size; v++){
                val = r.nextDouble() + d_beta;
                sum += val;
                m_beta[k][v] = val;
            }

            sum = Math.log(sum);
            for(int v = 0; v < vocabulary_size; v++){
                m_beta[k][v] = Math.log(m_beta[k][v]) - sum;
            }
        }

        this.m_etaStats = new double[number_of_topics];
        this.word_topic_sstat = new double[number_of_topics][vocabulary_size];
    }

    protected void initDoc(_Doc4ETBIR doc){
        doc.m_zeta = 1.0;
        doc.m_mu = new double[number_of_topics];
        doc.m_Sigma = new double[number_of_topics];
        doc.m_phi = new double[doc.getSparse().length][number_of_topics];
        Arrays.fill(doc.m_mu, 1);
        Arrays.fill(doc.m_Sigma, 0.1);
        for(int i=0;i < doc.getSparse().length;i++)
            Arrays.fill(doc.m_phi[i], 1.0/number_of_topics);
    }

    protected void initUser(_User4ETBIR user){
        user.m_nuP = new double[number_of_topics][number_of_topics];
        user.m_SigmaP = new double[number_of_topics][number_of_topics][number_of_topics];
        for(int k = 0; k < number_of_topics; k++){
            Arrays.fill(user.m_nuP[k], 1.0);
            for(int l = 0; l < number_of_topics; l++){
                Arrays.fill(user.m_SigmaP[k][l], 0.1);
                user.m_SigmaP[k][l][l] = 0.1;
            }
        }
    }

    protected void initItem(_Product4ETBIR item){
        item.m_eta = new double[number_of_topics];
        Arrays.fill(item.m_eta, d_alpha);
    }

    protected void initStats(){
        Arrays.fill(m_etaStats, 0.0);
        for(int k = 0; k < number_of_topics; k++)
            Arrays.fill(word_topic_sstat[k], 0);
        
        m_pStats = 0.0;
        m_thetaStats = 0.0;
        m_eta_p_Stats = 0.0;
        m_eta_mean_Stats = 0.0;
    }

    protected void updateStatsForItem(_Product4ETBIR item){
    	double digammaSum = Utils.digamma(Utils.sumOfArray(item.m_eta));
        for(int k = 0; k < number_of_topics;k++)
            m_etaStats[k] += Utils.digamma(item.m_eta[k]) - digammaSum;
    }

    protected void updateStatsForUser(_User4ETBIR user){
        for(int k = 0; k < number_of_topics; k++){
            for(int l = 0; l < number_of_topics; l++){
                m_pStats += user.m_SigmaP[k][l][l] + user.m_nuP[k][l] * user.m_nuP[k][l];
            }
        }
    }

    protected void updateStatsForDoc(_Doc4ETBIR doc){
        // update m_word_topic_stats for updating beta
        double delta = 1e-6;
        _SparseFeature[] fv = doc.getSparse();
        for(int k = 0; k < number_of_topics; k++){
            for(int n = 0; n < fv.length; n++){
                int wid = fv[n].getIndex();
                double v = fv[n].getValue();
                word_topic_sstat[k][wid] += v * doc.m_phi[n][k];
            }
        }

        // update m_thetaStats for updating rho
        for(int k = 0; k < number_of_topics; k++){
            m_thetaStats += doc.m_Sigma[k] + doc.m_mu[k] * doc.m_mu[k];
        }

        // update m_eta_p_stats for updating rho
        // update m_eta_mean_stats for updating rho
        _Product4ETBIR item = m_items[m_itemsIndex.get(doc.getItemID())];
        _User4ETBIR user = m_users[m_usersIndex.get(doc.getTitle())];
        for (int k = 0; k < number_of_topics; k++) {
            for (int l = 0; l < number_of_topics; l++) {
                m_eta_mean_Stats += item.m_eta[l] * user.m_nuP[k][l] * doc.m_mu[k];

                for (int j = 0; j < number_of_topics; j++) {
                    double term1 = user.m_SigmaP[k][l][j] + user.m_nuP[k][l] * user.m_nuP[k][j];
                    m_eta_p_Stats += item.m_eta[l] * item.m_eta[j] * term1;
                    if (j == l) {
                        term1 = user.m_SigmaP[k][l][j] + user.m_nuP[k][l] * user.m_nuP[k][j];
                        m_eta_p_Stats += item.m_eta[l] * term1;
                    }
                }
            }
        }
        double eta0 = Utils.sumOfArray(item.m_eta);
        m_eta_mean_Stats /= eta0;
        m_eta_p_Stats /= eta0 * (eta0 + 1.0);
    }

    protected double E_step(){

        int iter = 0;
        double totalLikelihood = 0.0, last = -1.0, converge = 0.0;

        do {
            initStats();
            totalLikelihood = 0.0;
            for (int i = 0; i < m_corpus.getCollection().size(); i++) {
                _Doc4ETBIR doc = (_Doc4ETBIR) m_corpus.getCollection().get(i);
//                System.out.println("***************** doc " + i + " ****************");
                String userID = doc.getTitle();
                String itemID = doc.getItemID();
                _User4ETBIR currentU = m_users[m_usersIndex.get(userID)];
                _Product4ETBIR currentI = m_items[m_itemsIndex.get(itemID)];

                double cur = varInferencePerDoc(doc, currentU, currentI);
                totalLikelihood += cur;
                updateStatsForDoc(doc);
            }

            for (int i = 0; i < m_users.length; i++) {
//                System.out.println("***************** user " + i + " ****************");
                _User4ETBIR user = m_users[i];

                double cur = varInferencePerUser(user);
                totalLikelihood += cur;
                updateStatsForUser(user);
            }

            for (int i = 0; i < m_items.length; i++) {
//                System.out.println("***************** item " + i + " ****************");
                _Product4ETBIR item = m_items[i];

                double cur = varInferencePerItem(item);
                totalLikelihood += cur;
                updateStatsForItem(item);
            }

            if(iter > 0) 
                converge = Math.abs((totalLikelihood - last) / last);
            else
                converge = 1.0;
            
            last = totalLikelihood;
            if(converge < m_varConverge)
                break;
        }while(iter++ < m_varMaxIter);

        return totalLikelihood;
    }

    protected double varInferencePerUser(_User4ETBIR u){
        double current = 0.0, last = 1.0, converge = 0.0;
        int iter = 0;

        do{
            update_SigmaP(u);
            update_nu(u);

            current = calc_log_likelihood_per_user(u);
            if(iter > 0){
                converge = (last - current) / last;
            }else{
                converge = 1.0;
            }
            last = current;
//            System.out.println("-- varInferencePerUser cur: " + current + "; converge: " + converge);
        } while(++iter < m_varMaxIter && Math.abs(converge) > m_varConverge);

        return current;
    }

    protected double varInferencePerItem(_Product4ETBIR i){
        double current = 0.0, last = 1.0, converge = 0.0;
        int iter = 0;

        do{
            update_eta(i);

            current = calc_log_likelihood_per_item(i);
            if (iter > 0)
                converge = (last - current) / last;
            else
                converge = 1.0;
            
            last = current;
//            System.out.println("-- varInferencePerItem cur: " + current + "; converge: " + converge);
        } while (++iter < m_varMaxIter && Math.abs(converge) > m_varConverge);

        return current;
    }

    protected double varInferencePerDoc(_Doc4ETBIR d, _User4ETBIR u, _Product4ETBIR i) {
        double current = 0.0, last = 1.0, converge = 0.0;
        int iter = 0;

        do {
            update_phi(d);
            update_zeta(d);
            update_mu(d, u ,i);
//            update_zeta(d);
            update_SigmaTheta(d);
            update_zeta(d);

            current = calc_log_likelihood_per_doc(d, u, i);
            if (iter > 0) {
                converge = (last-current) / last;
            }else{
                converge = 1.0;
            }
            last = current;
//            System.out.println("-- varInferencePerDoc cur: " + current + "; converge: " + converge);
        } while (++iter < m_varMaxIter && Math.abs(converge) > m_varConverge);

        return current;
    }

    //variational inference for p(z|w,\phi) for each document
    public void update_phi(_Doc4ETBIR d){
        double logSum;
        int wid;
        _SparseFeature[] fv = d.getSparse();

        for (int n = 0; n < fv.length; n++) {
            wid = fv[n].getIndex();
            for (int k = 0; k < number_of_topics; k++) 
                d.m_phi[n][k] = m_beta[k][wid] + d.m_mu[k];
            
            // normalize
            logSum = Utils.logSum(d.m_phi[n]);
            for (int k = 0; k < number_of_topics; k++) 
                d.m_phi[n][k] = Math.exp(d.m_phi[n][k] - logSum);
        }
    }

    //variational inference for p(\theta|\mu,\Sigma) for each document
    public void update_zeta(_Doc4ETBIR d){
        //estimate zeta
        d.m_zeta = 0;
        for (int k = 0; k < number_of_topics; k++) 
            d.m_zeta += Math.exp(d.m_mu[k] + 0.5 * d.m_Sigma[k]);
    }

    // alternative: line search / fixed-stepsize gradient descent
    public void update_mu(_Doc4ETBIR doc, _User4ETBIR user, _Product4ETBIR item){
        double fValue = 1.0, lastFValue = 1.0, cvg = 1e-4, diff, iterMax = 60, iter = 0;
        double stepsize = 1e-3;
        double[] muG = new double[number_of_topics]; // gradient for mu
        int N = doc.getTotalDocLength();

        double[] m_phiStat = new double[number_of_topics];
        _SparseFeature[] fv = doc.getSparse();
        for(int k = 0;k < number_of_topics; k++) {
            for (int n = 0; n < fv.length; n++) {
                double v = fv[n].getValue();
                m_phiStat[k] += v * doc.m_phi[n][k];
            }
        }

        double moment, zeta_stat = 1.0 / doc.m_zeta;
        double etaSum = Utils.sumOfArray(item.m_eta);
        
        do {
            //update gradient of mu
            lastFValue = fValue;
            fValue = 0.0;
            for (int k = 0; k < number_of_topics; k++) {
                moment = Math.exp(doc.m_mu[k] + 0.5 * doc.m_Sigma[k]);
                muG[k] = -(-m_rho * (doc.m_mu[k] - Utils.dotProduct(item.m_eta, user.m_nuP[k]) / etaSum)
                        + m_phiStat[k] - N * zeta_stat * moment);//-1 because LBFGS is minimization
                fValue += -(-0.5 * m_rho * (doc.m_mu[k] * doc.m_mu[k]
                        - 2 * doc.m_mu[k] * Utils.dotProduct(item.m_eta, user.m_nuP[k]) / etaSum)
                        + doc.m_mu[k] * m_phiStat[k] - N * zeta_stat * moment);
            }

            //fix stepsize
            for(int k=0;k < number_of_topics;k++) {
                doc.m_mu[k] = doc.m_mu[k] - stepsize * muG[k];
            }
            diff = (lastFValue - fValue) / lastFValue;
        } while (iter++ < iterMax && Math.abs(diff) > cvg);
    }

    public void update_SigmaTheta(_Doc4ETBIR d){
        double fValue = 1.0, lastFValue = 1.0, cvg = 1e-6, diff, iterMax = 20, iter = 0;
        double stepsize = 1e-3;
        int N = d.getTotalDocLength();
        double[] SigmaG = new double[number_of_topics]; // gradient for Sigma

        double[] sigmaSqrt = new double[number_of_topics];
        for(int k=0; k < number_of_topics; k++)
            sigmaSqrt[k] = Math.sqrt(d.m_Sigma[k]);

        do {
            //update gradient of sigma
            lastFValue = fValue;
            fValue = 0.0;

            double moment, sigma;
            for (int k = 0; k < number_of_topics; k++) {
                sigma = sigmaSqrt[k] * sigmaSqrt[k];
                moment = Math.exp(d.m_mu[k] + 0.5 * sigma);
                //this gradient is inconsistent with the derivation
                SigmaG[k] = -(-m_rho * sigmaSqrt[k] - N * sigmaSqrt[k] * moment / d.m_zeta + 1.0 / sigmaSqrt[k]); //-1 because LBFGS is minimization
                fValue += -(-0.5 * m_rho * sigma - N * moment / d.m_zeta + 0.5 * Math.log(sigma));
            }

            //fixed stepsize
            for(int k = 0; k < number_of_topics;k ++) {
                sigmaSqrt[k] = sigmaSqrt[k] - stepsize * SigmaG[k];
            }

            diff = (lastFValue - fValue) / lastFValue;
        } while(iter++ < iterMax && Math.abs(diff) > cvg);

        for(int k=0; k < number_of_topics; k++){
            d.m_Sigma[k] = Math.pow(sigmaSqrt[k], 2);
        }

    }

    //variational inference for p(P|\nu,\Sigma) for each user
    public void update_SigmaP(_User4ETBIR u){
        ArrayList<Integer> Iu = m_mapByUser.get(m_usersIndex.get(u.getUserID()));
        RealMatrix eta_stat_sigma = MatrixUtils.createRealIdentityMatrix(number_of_topics).scalarMultiply(m_sigma);

        for (Integer itemIdx : Iu) {
            _Product4ETBIR item = m_items[itemIdx];

            RealMatrix eta_vec = MatrixUtils.createColumnRealMatrix(item.m_eta);
            double eta_0 = Utils.sumOfArray(item.m_eta);
            RealMatrix eta_stat_i = MatrixUtils.createRealDiagonalMatrix(item.m_eta).add(
                    eta_vec.multiply(eta_vec.transpose()));

            eta_stat_sigma = eta_stat_sigma.add(eta_stat_i.scalarMultiply(m_rho / (eta_0 * (eta_0 + 1.0))));
        }
        eta_stat_sigma = new LUDecomposition(eta_stat_sigma).getSolver().getInverse();
        for (int k = 0; k < number_of_topics; k++) {
            u.m_SigmaP[k] = eta_stat_sigma.getData();
        }
    }

    //variational inference for p(P|\nu,\Sigma) for each user
    public void update_nu(_User4ETBIR u){
        ArrayList<Integer> Iu = m_mapByUser.get(m_usersIndex.get(u.getUserID()));
        RealMatrix eta_stat_sigma = MatrixUtils.createRealMatrix(u.m_SigmaP[0]);

//        System.out.println("-- update nuP: origin: " + Arrays.toString(u.m_nuP[0]));
        for (int k = 0; k < number_of_topics; k++) {
            RealMatrix eta_stat_nu = MatrixUtils.createColumnRealMatrix(new double[number_of_topics]);

            for (Integer itemIdx : Iu) {
                _Product4ETBIR item = m_items[itemIdx];
                _Doc4ETBIR d = (_Doc4ETBIR) m_corpus.getCollection().get(m_reviewIndex.get(itemIdx + "_"
                        + m_usersIndex.get(u.getUserID())));

                RealMatrix eta_vec = MatrixUtils.createColumnRealMatrix(item.m_eta);
                double eta_0 = Utils.sumOfArray(item.m_eta);
                eta_stat_nu = eta_stat_nu.add(eta_vec.scalarMultiply(d.m_mu[k] / eta_0));
            }
            u.m_nuP[k] = eta_stat_sigma.multiply(eta_stat_nu).scalarMultiply(m_rho).getColumn(0);
        }
//        System.out.println("-- update nuP: origin: " + Arrays.toString(u.m_nuP[0]));
    }

    public void update_eta(_Product4ETBIR i){
        ArrayList<Integer> Ui = m_mapByItem.get(m_itemsIndex.get(i.getID()));

        double fValue = 1.0, lastFValue = 1.0, cvg = 1e-6, diff, iterMax = 20, iter = 0;
        double stepsize = 1e3;
        double[] etaG = new double[number_of_topics];

        do{
            lastFValue = fValue;
            fValue = 0.0;
            double check;

            double eta0 = Utils.sumOfArray(i.m_eta);
            double lgGammaEta = Utils.lgamma(eta0);
            double diGammaEta = Utils.digamma(eta0);
            double trGammaEta = Utils.trigamma(eta0);
            for(int k = 0; k < number_of_topics; k++) {
                //might be optimized using global stats
                double gTerm1 = 0.0;
                double gTerm2 = 0.0;
                double gTerm3 = 0.0;
                double gTerm4 = 0.0;
                double term1 = 0.0;
                double term2 = 0.0;
                for (Integer userIdx : Ui) {
                    _User4ETBIR user = m_users[userIdx];
                    _Doc4ETBIR d = (_Doc4ETBIR) m_corpus.getCollection().get(m_reviewIndex.get(
                            m_itemsIndex.get(i.getID()) + "_" + userIdx));

                    for (int j = 0; j < number_of_topics; j++) {
                        gTerm1 += user.m_nuP[j][k] * d.m_mu[j];
                        term1 += i.m_eta[k] * user.m_nuP[j][k] * d.m_mu[j];

                        for (int l = 0; l < number_of_topics; l++) {
                            gTerm2 += i.m_eta[l] * user.m_nuP[j][l] * d.m_mu[j];

                            gTerm3 += i.m_eta[l] * (user.m_SigmaP[j][l][k]
                                    + user.m_nuP[j][l] * user.m_nuP[j][k]);
                            if (l == k) {
                                gTerm3 += (i.m_eta[l] + 1.0) * (user.m_SigmaP[j][l][k]
                                        + user.m_nuP[j][l] * user.m_nuP[j][k]);
                            }

                            term2 += i.m_eta[l] * i.m_eta[k] * (user.m_SigmaP[j][l][k]
                                    + user.m_nuP[j][l] * user.m_nuP[j][k]);
                            if (l == k) {
                                term2 += i.m_eta[k] * (user.m_SigmaP[j][k][k]
                                        + user.m_nuP[j][k] * user.m_nuP[j][k]);
                            }

                            for (int p = 0; p < number_of_topics; p++) {
                                gTerm4 += i.m_eta[l] * i.m_eta[p] * (user.m_SigmaP[j][l][p]
                                        + user.m_nuP[j][l] * user.m_nuP[j][p]);
                                if (p == l) {
                                    gTerm4 += i.m_eta[p] * (user.m_SigmaP[j][l][p]
                                            + user.m_nuP[j][l] * user.m_nuP[j][p]);
                                }
                            }
                        }
                    }
                }
                
                etaG[k] = -(Utils.trigamma(i.m_eta[k]) * (m_alpha[k] - i.m_eta[k])
                        - trGammaEta * (Utils.sumOfArray(m_alpha) - eta0)
                        + m_rho * gTerm1 / eta0 - m_rho * gTerm2 / (eta0 * eta0)
                        - m_rho * gTerm3 / (2 * eta0 * (eta0 + 1.0))
                        + m_rho * (2 * eta0 + 1.0) * gTerm4 / (2 * eta0 * eta0 
                        * (eta0 + 1.0) * (eta0 + 1.0)));

                fValue += -((m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEta)
                        - lgGammaEta + Utils.lgamma(i.m_eta[k])
                        + m_rho * term1 / eta0 - m_rho * term2 / (2 * eta0 * (eta0 + 1.0)));
                
                if(k == 0) {
                    double eps = 1e-12;
                    i.m_eta[k] = i.m_eta[k] + eps;
                    double post = -((m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEta)
                            - lgGammaEta + Utils.lgamma(i.m_eta[k])
                            + m_rho * term1 / eta0 - m_rho * term2 / (2 * eta0 * (eta0 + 1.0)));
                    
                    i.m_eta[k] = i.m_eta[k] - eps;
                    double pre = -((m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEta)
                            - lgGammaEta + Utils.lgamma(i.m_eta[k])
                            + m_rho * term1 / eta0 - m_rho * term2 / (2 * eta0 * (eta0 + 1.0)));
                    check = (post - pre) / eps;//gradient?
                }
            }
            // fix stepsize
            for(int k = 0; k < number_of_topics; k++) 
                i.m_eta[k] = i.m_eta[k] - stepsize * etaG[k];

            diff = (lastFValue - fValue) / lastFValue;
        }while(iter++ < iterMax && Math.abs(diff) > cvg);
    }

    public void M_step() {
        //maximize likelihood for \rho of p(\theta|P\gamma, \rho)
        m_rho = number_of_topics / (m_thetaStats + m_eta_p_Stats - 2 * m_eta_mean_Stats);

        //maximize likelihood for \sigma
        m_sigma = number_of_topics / m_pStats;

        //maximize likelihood for \beta
        for(int k = 0 ;k < number_of_topics; k++){
            double sum = Math.log(Utils.sumOfArray(word_topic_sstat[k]));
            for(int v = 0; v < vocabulary_size; v++)
                m_beta[k][v] = Math.log(word_topic_sstat[k][v]) - sum;
        }

        //maximize likelihood for \alpha using Newton
        int i = 0;
        double diff = 0.0, alphaSum, diAlphaSum, z, c1, c2, c, deltaAlpha;
        do{
            alphaSum = Utils.sumOfArray(m_alpha);
            diAlphaSum = Utils.digamma(alphaSum);
            z = number_of_items * Utils.trigamma(alphaSum);

            c1 = 0; c2 = 0;
            for(int k = 0; k < number_of_topics; k++){
                m_alphaG[k] = number_of_items * (diAlphaSum - Utils.digamma(m_alpha[k])) + m_etaStats[k];
                m_alphaH[k] = - number_of_items * Utils.trigamma(m_alpha[k]);

                c1 += m_alphaG[k] / m_alphaH[k];
                c2 += 1.0 / m_alphaH[k];
            }
            c = c1 / (1.0/z + c2);

            diff = 0.0;
            for(int k = 0; k < number_of_topics; k++){
                deltaAlpha = (m_alphaG[k] -c) / m_alphaH[k];
                m_alpha[k] -= deltaAlpha;
                diff += deltaAlpha * deltaAlpha;
            }
            diff /= number_of_topics;
        }while(++i < m_varMaxIter && diff > m_varConverge);

    }

    // calculate the likelihood of user-related terms (term2-term7)
    protected double calc_log_likelihood_per_user(_User4ETBIR u){
        double log_likelihood = 0.0;

        for(int k = 0; k < number_of_topics; k++){
            double temp1 = 0.0;
            for(int l = 0; l < number_of_topics; l++) {
                temp1 += u.m_SigmaP[k][l][l] + u.m_nuP[k][l] * u.m_nuP[k][l];
            }
            double det = new LUDecomposition(MatrixUtils.createRealMatrix(u.m_SigmaP[k])).getDeterminant();
            log_likelihood += -0.5 * (temp1 * m_sigma - number_of_topics)
                    + 0.5 * (number_of_topics * Math.log(m_sigma) + Math.log(det));
        }

        return log_likelihood;
    }

    // calculate the likelihood of item-related terms (term1-term6)
    protected double calc_log_likelihood_per_item(_Product4ETBIR i){
        double log_likelihood = 0.0;
        double eta0 = Utils.sumOfArray(i.m_eta);
        double diGammaEtaSum = Utils.digamma(eta0);
        double lgammaEtaSum = Utils.lgamma(eta0);
        double lgammaAlphaSum = Utils.lgamma(Utils.sumOfArray(m_alpha));

        for(int k = 0; k < number_of_topics; k++){
            log_likelihood += (m_alpha[k] - i.m_eta[k]) * (Utils.digamma(i.m_eta[k]) - diGammaEtaSum);
            log_likelihood -= Utils.lgamma(m_alpha[k]) - Utils.lgamma(i.m_eta[k]);
        }
        log_likelihood += lgammaAlphaSum - lgammaEtaSum;

        return log_likelihood;
    }

    // calculate the likelihood of doc-related terms (term3-term8 + term4-term9 + term5)
    protected double calc_log_likelihood_per_doc(_Doc4ETBIR doc, _User4ETBIR currentU, _Product4ETBIR currentI) {

        double log_likelihood = 0.0;
        double eta0 = Utils.sumOfArray(currentI.m_eta);

        // (term3-term8)
        double term1 = 0.0;
        double term2 = 0.0;
        double term3 = 0.0;
        double term4 = 0.0;
        double part3 = 0.0;
        for(int k = 0; k < number_of_topics; k++){
            term1 += doc.m_Sigma[k] + doc.m_mu[k] * doc.m_mu[k];
            for(int j = 0; j < number_of_topics; j++){
                term2 += currentI.m_eta[k] * currentU.m_nuP[j][k] * doc.m_mu[j];

                for(int l = 0; l < number_of_topics; l++){
                    term3 += currentI.m_eta[j] * currentI.m_eta[l] *
                            (currentU.m_SigmaP[k][j][l] + currentU.m_nuP[k][j] * currentU.m_nuP[k][l]);
                    if(l == j){
                        term3 += currentI.m_eta[l] *
                                (currentU.m_SigmaP[k][j][l] + currentU.m_nuP[k][j] * currentU.m_nuP[k][l]);
                    }
                }
            }
            term4 += Math.log(m_rho * doc.m_Sigma[k]);
        }
        part3 += -m_rho * (0.5 * term1 - term2 / eta0 + term3 / (eta0 * (eta0 + 1.0))) + number_of_topics/2.0
                + 0.5 * term4;
        log_likelihood += part3;

        //part4
        int wid;
        double v;
        double part4 = 0.0, part5 = 0.0;
        term1 = 0.0;
        term2 = 0.0;
        term3 = 0.0;
        _SparseFeature[] fv = doc.getSparse();
//        System.out.println("file length: " + fv.length);
        for(int k = 0; k < number_of_topics; k++) {
            for (int n = 0; n < fv.length; n++) {
                wid = fv[n].getIndex();
                v = fv[n].getValue();
                term1 += v * doc.m_phi[n][k] * doc.m_mu[k];
                term3 += v * doc.m_phi[n][k] * Math.log(doc.m_phi[n][k]);
                part5 += v * doc.m_phi[n][k] * m_beta[k][wid];
            }
            term2 += Math.exp(doc.m_mu[k] + doc.m_Sigma[k]/2.0);
        }
        part4 += term1 - doc.getTotalDocLength() * ( term2 / doc.m_zeta - 1.0 + Math.log(doc.m_zeta)) - term3;
        log_likelihood += part4;
        log_likelihood += part5;

        return log_likelihood;
    }

    @Override
    public void EM(){
        System.out.println("Initializing model...");
        initModel();

        System.out.println("Initializing documents...");
        for(_Doc doc : m_corpus.getCollection())
            initDoc((_Doc4ETBIR) doc);
        
        System.out.println("Initializing users...");
        for(_User user : m_users)
            initUser((_User4ETBIR) user);
        
        System.out.println("Initializing items...");
        for(_Product item : m_items)
            initItem((_Product4ETBIR) item);

        int iter = 0;
        double lastAllLikelihood = 1.0;
        double currentAllLikelihood;
        double converge = 0.0;
        do{
            currentAllLikelihood = E_step();
            for(int k = 0; k < number_of_topics;k++){
                for(int v=0; v < vocabulary_size; v++){
                    word_topic_sstat[k][v] += this.d_beta;
                }
            }
            
            if(iter > 0)
                converge = (lastAllLikelihood - currentAllLikelihood) / lastAllLikelihood;
            else
                converge = 1.0;

            if(converge < 0){
                m_varMaxIter += 10;
                System.out.println("! E_step not converge...");
            }else{
                M_step();
                lastAllLikelihood = currentAllLikelihood;
                System.out.format("%s step: likelihood is %.3f, converge to %f...\n",
                        iter, currentAllLikelihood, converge);
                iter++;
                if(converge < m_converge)
                    break;
            }
            System.out.println("sigma: " + m_sigma + "; rho: " + m_rho);
            System.out.println(Utils.sumOfArray(word_topic_sstat[0]));
        }while(iter < number_of_iteration && (converge < 0 || converge > m_converge));
    }

    public void printEta(String etafile){
        try{
            PrintWriter etaWriter = new PrintWriter(new File(etafile));

            for(int idx = 0; idx < m_items.length; idx++) {
                etaWriter.write("item " + idx + "*************\n");
                _Product4ETBIR item = (_Product4ETBIR) m_items[idx];
                etaWriter.format("-- eta: \n");
                for (int i = 0; i < number_of_topics; i++) {
                    etaWriter.format("%.8f\t", item.m_eta[i]);
                }
                etaWriter.write("\n");
            }
            etaWriter.close();
        } catch(Exception ex){
            System.err.print("File Not Found");
        }
    }

    public void printP(String pfile){
        try{
            PrintWriter pWriter = new PrintWriter(new File(pfile));

            for(int idx = 0; idx < m_users.length; idx++) {
                pWriter.write("user " + idx + "*************\n");
                _User4ETBIR user = (_User4ETBIR) m_users[idx];
                for (int i = 0; i < number_of_topics; i++) {
                    pWriter.format("-- mu " + i + ": \n");
                    for(int k = 0; k < number_of_topics; k++) {
                        pWriter.format("%.5f\t", user.m_nuP[i][k]);
                    }
                    pWriter.write("\n");
                }
            }
            pWriter.close();
        } catch(Exception ex){
            System.err.print("File Not Found");
        }
    }

    public void printTopWords(int k, String topWordPath) {
        System.out.println("TopWord FilePath:" + topWordPath);
        Arrays.fill(m_sstat, 0);
        for(int d = 0; d < m_corpus.getCollection().size(); d++) {
            _Doc4ETBIR doc = (_Doc4ETBIR) m_corpus.getCollection().get(d);
            for(int i=0; i<number_of_topics; i++)
                m_sstat[i] += Math.exp(doc.m_mu[i]);
        }
        Utils.L1Normalization(m_sstat);

        try{
            PrintWriter topWordWriter = new PrintWriter(new File(topWordPath));

            for(int i=0; i<topic_term_probabilty.length; i++) {
                MyPriorityQueue<_RankItem> fVector = new MyPriorityQueue<_RankItem>(k);
                for(int j = 0; j < vocabulary_size; j++)
                    fVector.add(new _RankItem(m_corpus.getFeature(j), m_beta[i][j]));

                topWordWriter.format("Topic %d(%.5f):\t", i, m_sstat[i]);
                for(_RankItem it:fVector)
                    topWordWriter.format("%s(%.5f)\t", it.m_name, Math.exp(it.m_value));
                topWordWriter.write("\n");
            }
            topWordWriter.close();
        } catch(Exception ex){
            System.err.print("File Not Found");
        }
    }


}
