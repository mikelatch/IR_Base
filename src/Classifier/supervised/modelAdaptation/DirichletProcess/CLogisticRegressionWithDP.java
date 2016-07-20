package Classifier.supervised.modelAdaptation.DirichletProcess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import Classifier.supervised.modelAdaptation._AdaptStruct;
import Classifier.supervised.modelAdaptation.CoLinAdapt.LinAdapt;
import LBFGS.LBFGS;
import LBFGS.LBFGS.ExceptionWithIflag;
import cern.jet.random.tdouble.Normal;
import cern.jet.random.tdouble.engine.DoubleMersenneTwister;
import structures._Doc;
import structures._SparseFeature;
import structures._User;
import structures._thetaStar;
import utils.Utils;

public class CLogisticRegressionWithDP extends LinAdapt {
	protected boolean m_burnInM = true; // Whether we have M step in burn in period.
	
	protected Normal m_normal; // Normal distribution.
	protected int m_M = 5, m_kBar = 0; // The number of auxiliary components.
	protected int m_numberOfIterations = 10;
	protected int m_burnIn = 10, m_thinning = 3;// burn in time, thinning time.
	protected double m_converge = 1e-9;
	protected double m_alpha = 0.001; // Scaling parameter of DP.
	protected double m_pNewCluster; // to be assigned before EM starts
	
	// Parameters of the prior for the intercept and coefficients.
	protected double[] m_abNuA = new double[]{0, 0.9};
	protected double[] m_models; // model parameters for clusters.
	protected double[] m_probs;
	public static _thetaStar[] m_thetaStars = new _thetaStar[1000];//to facilitate prediction in each user 

	public CLogisticRegressionWithDP(int classNo, int featureSize, HashMap<String, Integer> featureMap, String globalModel){
		super(classNo, featureSize, featureMap, globalModel, null);
		m_dim = m_featureSize + 1; // to add the bias term
		m_normal = new Normal(0, 1, new DoubleMersenneTwister());
	}

	protected void accumulateClusterModels(){
		m_models = new double[getVSize()];//very inefficient, a per cluster optimization procedure will not have this problem
		for(int i=0; i<m_kBar; i++){
			System.arraycopy(m_thetaStars[i].getModel(), 0, m_models, m_dim*i, m_dim);
		}
	}
	
	protected void assignClusterIndex(){
		for(int i=0; i<m_kBar; i++)
			m_thetaStars[i].setIndex(i);
	}
	
	// After we finish estimating the clusters, we calculate the probability of each user belongs to each cluster.
	protected void calculateClusterProbPerUser(){
		double prob;
		_DPAdaptStruct user;
		for(int i=0; i<m_userList.size(); i++){
			user = (_DPAdaptStruct) m_userList.get(i);
			for(int k=0; k<m_kBar; k++){
				user.setThetaStar(m_thetaStars[k]);
				prob = calcLogLikelihood(user) + Math.log(m_thetaStars[k].getMemSize());
				m_probs[k] = Math.exp(prob);//this will be in real space!
			}
			Utils.L1Normalization(m_probs);
			user.setClusterPosterior(m_probs);
		}
	}
	
	protected double calculateR1(){
		double v, R1 = 0;
		for(int i=0; i<m_models.length; i++) {
			v = (m_models[i]-m_abNuA[0]) / m_abNuA[1];
			R1 += v*v;
		}
		return R1;
	}
	
	int findThetaStar(_thetaStar theta) {
		for(int i=0; i<m_kBar; i++)
			if (theta == m_thetaStars[i])
				return i;
		return -1;// impossible to hit here!
	}
	
	// Sample thetaStars.
	protected void sampleThetaStars(){
		for(int m=m_kBar; m<m_kBar+m_M; m++){
//			if (m_thetaStars[m] == null)
				m_thetaStars[m] = new _thetaStar(m_dim, m_abNuA);
			m_thetaStars[m].sampleBeta(m_normal);
		}
	}
	
	// Sample one instance's cluster assignment.
	protected void sampleOneInstance(_DPAdaptStruct user){
		double likelihood, logSum = 0;
		int k;
		
		//reset thetaStars
		sampleThetaStars();
		for(k=0; k<m_kBar+m_M; k++){
			user.setThetaStar(m_thetaStars[k]);
			likelihood = calcLogLikelihood(user);
			
			if (k<m_kBar)
				likelihood += Math.log(m_thetaStars[k].getMemSize());
			else
				likelihood += m_pNewCluster;
			 
			m_thetaStars[k].setProportion(likelihood);//this is in log space!
			
			if (k==0)
				logSum = likelihood;
			else
				logSum = Utils.logSum(logSum, likelihood);
		}
		
		logSum += Math.log(Math.random());//we might need a better random number generator
		
		double newLogSum = m_thetaStars[0].getProportion();
		for(k=1; k<m_kBar+m_M && newLogSum<logSum; k++)
			newLogSum = Utils.logSum(newLogSum, m_thetaStars[k].getProportion());
		
		if (k==m_kBar+m_M)
			k--; // we might hit the very last
		
		m_thetaStars[k].updateMemCount(1);
		user.setThetaStar(m_thetaStars[k]);
		if(k >= m_kBar){
			m_thetaStars[m_kBar] = m_thetaStars[k];
			m_kBar++;
		}
	}	
	
	// The main MCMC algorithm, assign each user to clusters.
	protected void calculate_E_step(){
		int cIndex;
		_thetaStar curThetaStar;
		_DPAdaptStruct user;
		
		for(int i=0; i<m_userList.size(); i++){
			user = (_DPAdaptStruct) m_userList.get(i);
			curThetaStar = user.getThetaStar();
			curThetaStar.updateMemCount(-1);
			
			if(curThetaStar.getMemSize() == 0){// No data associated with the cluster.
				cIndex = findThetaStar(curThetaStar);
				m_thetaStars[cIndex] = m_thetaStars[m_kBar-1]; // Use the last thetastar to cover this one.
				m_kBar--;// kBar starts from 0, the size decides how many are valid.
			}
			sampleOneInstance(user);
		}
	}
	
	// Sample the weights given the cluster assignment.
	protected double calculate_M_step(){
		int[] iflag = {0}, iprint = {-1, 3};
		double fValue, oldFValue = Double.MAX_VALUE;
		int displayCount = 0;
		_DPAdaptStruct user;
		
		initLBFGS();// Init for lbfgs.
		assignClusterIndex();
		try{
			do{
				fValue = 0;
				initPerIter();
				// Use instances inside one cluster to update the thetastar.
				for(int i=0; i<m_userList.size(); i++){
					user = (_DPAdaptStruct) m_userList.get(i);
					fValue -= calcLogLikelihood(user);
					gradientByFunc(user); // calculate the gradient by the user.
				}
				
				accumulateClusterModels();
				fValue += calculateR1();
				gradientByR1();
				
				if (m_displayLv==2) {
					gradientTest();
					System.out.print("Fvalue is " + fValue + "\t");
				} else if (m_displayLv==1) {
					if (fValue<oldFValue)
						System.out.print("o");
					else
						System.out.print("x");
					
					if (++displayCount%100==0)
						System.out.println();
				} 
				LBFGS.lbfgs(m_g.length, 5, m_models, fValue, m_g, false, m_diag, iprint, 1e-3, 1e-16, iflag);//In the training process, A is updated.
				setThetaStars();
				oldFValue = fValue;
				
			} while(iflag[0] != 0);
			System.out.println();
		} catch(ExceptionWithIflag e) {
			System.out.println("LBFGS fails!!!!");
			e.printStackTrace();
		}		
		
		setPersonalizedModel();
		return oldFValue;
	}
	
	// The main EM algorithm to optimize cluster assignment and distribution parameters.
	public void EM(){
		System.out.println(toString());
		double delta = 0, lastLikelihood = 0, curLikelihood = 0;
		int count = 0;
		
		initThetaStars();// Init cluster assignment.
		init(); // clear user performance.
		
		// Burn in period.
		while(count++ < m_burnIn){
			calculate_E_step();
			if(m_burnInM)
				calculate_M_step();
		}
		
		// EM iteration.
		for(int i=0; i<m_numberOfIterations; i++){
			// Cluster assignment, thinning to reduce auto-correlation.
			for(int j=0; j<m_thinning; j++)
				calculate_E_step();
			// Optimize the parameters
			curLikelihood = calculate_M_step();

			delta = curLikelihood - lastLikelihood;
			System.out.print(String.format("[Info]Step %d: Delta_likelihood: %.3f\n", i, delta));
			if(Math.abs(delta) < m_converge)
				break;
			lastLikelihood = curLikelihood;
		}
	}
	
	@Override
	protected int getVSize() {
		return m_kBar*m_dim;
	}
	
	@Override
	protected void gradientByFunc(_AdaptStruct u, _Doc review, double weight) {
		_DPAdaptStruct user = (_DPAdaptStruct)u;
		
		int n; // feature index
		int cIndex = user.getThetaStar().getIndex();
		if(cIndex <0 || cIndex >= m_kBar)
			System.err.println("Error,cannot find the theta star!");
		int offset = m_dim*cIndex;
		double delta = (review.getYLabel() - logit(review.getSparse(), user));
		if(m_LNormFlag)
			delta /= getAdaptationSize(user);
		
		//Bias term.
		m_g[offset] -= weight*delta; //x0=1

		//Traverse all the feature dimension to calculate the gradient.
		for(_SparseFeature fv: review.getSparse()){
			n = fv.getIndex() + 1;
			m_g[offset + n] -= weight * delta * fv.getValue();
		}
	}
	
	// Gradient by the regularization.
	protected void gradientByR1(){
		for(int i=0; i<m_g.length; i++)
			m_g[i] += 2*(m_models[i]-m_abNuA[0]) / (m_abNuA[1]*m_abNuA[1]);
	}
	
	@Override
	protected double gradientTest() {
		double mag = 0 ;
		for(int i=0; i<m_g.length; i++)
			mag += m_g[i]*m_g[i];

		if (m_displayLv==2)
			System.out.format("Gradient magnitude: %.5f\n", mag);
		return mag;
	}
	
	// Assign cluster assignment to each user.
	protected void initThetaStars(){
		m_pNewCluster = Math.log(m_alpha) - Math.log(m_M);
		
		_DPAdaptStruct user;
		for(int i=0; i<m_userList.size(); i++){
			user = (_DPAdaptStruct) m_userList.get(i);
			sampleOneInstance(user);
		}		
	}
	
	@Override
	protected void initLBFGS(){
		m_g = new double[getVSize()];
		m_diag = new double[getVSize()];
		Arrays.fill(m_g, 0);
		Arrays.fill(m_diag, 0);
	}
	
	// Init in each iteration in M step.
	protected void initPerIter() {
		Arrays.fill(m_g, 0); // initialize gradient
		Arrays.fill(m_diag, 0);
	}
	
	@Override
	public void loadUsers(ArrayList<_User> userList) {
		m_userList = new ArrayList<_AdaptStruct>();
		
		for(_User user:userList)
			m_userList.add(new _DPAdaptStruct(user));
		m_pWeights = new double[m_gWeights.length];		
	}
	
	@Override	
	protected double logit(_SparseFeature[] fvs, _AdaptStruct u){
		double sum = Utils.dotProduct(((_DPAdaptStruct)u).getThetaStar().getModel(), fvs, 0);
		return Utils.logistic(sum);
	}

	// Set a bunch of parameters.
	public void setAlpha(double a){
		m_alpha = a;
	}
	
	public void setBurnIn(int n){
		m_burnIn = n;
	}
	
	public void setBurnInM(boolean b){
		m_burnInM = b;
	}
	
	public void setM(int m){
		m_M = m;
	}
	
	protected void setNumberOfIterations(int num){
		m_numberOfIterations = num;
	}
	
	@Override
	protected void setPersonalizedModel() {
		_DPAdaptStruct user;
		for(int i=0; i<m_userList.size(); i++){
			user = (_DPAdaptStruct) m_userList.get(i);
			user.setPersonalizedModel(user.getThetaStar().getModel());
		}
	}
	
	// Assign the optimized weights to the cluster.
	protected void setThetaStars(){
		double[] beta;
		for(int i=0; i<m_kBar; i++){
			beta = m_thetaStars[i].getModel();
			for(int n=0; n<m_dim; n++)
				beta[n] = m_models[i*m_dim + n];
		}
	}
	
//	protected int m_test = 10;// We are trying to get the expectation of the performance.
//	double[][] m_perfs;
//	
	// In testing phase, we need to sample several times and get the average.
//	@Override
//	public double test(){
//		m_perfs = new double[m_test][];
//		m_M = 0;// We don't introduce new clusters.
//		for(int i=0; i<m_test; i++){
//			calculate_E_step();
//			setPersonalizedModel();
//			super.test();
//			m_perfs[i] = Arrays.copyOf(m_perf, m_perf.length);
//			clearPerformance();
//		}
//		double[] avgPerf = new double[m_classNo*2];
//		System.out.print("[Info]Avg Perf:");
//		for(int i=0; i<m_perfs[0].length; i++){
//			avgPerf[i] = Utils.sumOfColumn(m_perfs, i)/m_test*1.0;
//			System.out.print(String.format("%.5f\t", avgPerf[i]));
//		}
//		System.out.println();
//		return 0;
//	}
//	
//	protected void clearPerformance(){
//		Arrays.fill(m_perf, 0);
//		m_microStat.clear();
//		for(_AdaptStruct u: m_userList)
//			u.getUser().getPerfStat().clear();
//	}
	
	@Override
	public double test(){
		// we calculate the user's cluster probability.
		m_probs = new double[m_kBar];
		calculateClusterProbPerUser();
		super.test();	
		return 0;
	}

	
	@Override
	public String toString() {
		return String.format("CLRWithDP[dim:%d,M:%d,alpha:%.4f,#Iter:%d]", m_dim, m_M, m_alpha, m_numberOfIterations);
	}
	
	public void printInfo(){
		int[] clusters = new int[m_kBar];
		for(int i=0; i<m_kBar; i++)
			clusters[i] = m_thetaStars[i].getMemSize();
		Arrays.sort(clusters);
		System.out.print("[Info]Clusters:");
		for(int i: clusters)
			System.out.print(i+"\t");
		System.out.println();
		System.out.print(String.format("[Info]%d, %d Clusters are found in total!\n", m_kBar, Utils.sumOfArray(clusters)));
	}
}
