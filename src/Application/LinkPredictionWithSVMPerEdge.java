package Application;

import java.util.ArrayList;
import java.util.HashMap;

import Classifier.supervised.SVM;
import Classifier.supervised.liblinear.Feature;
import Classifier.supervised.liblinear.FeatureNode;
import Classifier.supervised.liblinear.Linear;
import Classifier.supervised.liblinear.Model;
import Classifier.supervised.liblinear.Parameter;
import Classifier.supervised.liblinear.Problem;
import Classifier.supervised.liblinear.SolverType;
import Classifier.supervised.modelAdaptation.MMB._MMBAdaptStruct;

public class LinkPredictionWithSVMPerEdge extends LinkPredictionWithMMBPerEdge {
	double m_C = 0;
	Model m_libModel;
	double m_rho;
	
	public LinkPredictionWithSVMPerEdge(double c, double rho, HashMap<String, String[]> trainMap, HashMap<String, String[]> testMap){
		super(trainMap, testMap);
		m_C = c;
		m_rho = rho;
	}
	
	@Override
	public void linkPrediction(){
		initLinkPred();
		calculateMixturePerUser();

		trainSVM();
		// calculate the symmetric similarity between user pairs
		for(int i=0; i<m_testSize; i++){
			_MMBAdaptStruct ui = m_testSet.get(i);
			for(int j=i+1; j<m_testSize; j++){
				_MMBAdaptStruct uj = m_testSet.get(j);
				double sim = calcSimilarity(ui, uj);
				m_simMtx[i][j] = sim;
				m_simMtx[j][i] = sim;
			}
		}
				
		// for each user, rank their neighbors.
		int testUser = 0;
		for(int i=0; i<m_testSize; i++){
			_MMBAdaptStruct ui = m_testSet.get(i);
			if(m_testMap.containsKey(ui.getUserID()) && ui.getUser().getTestFriendSize() != 0){
				linkPrediction4TestUsers(i, ui);
				testUser++;
			}
		}
		System.out.format("[Info]Finish link prediction on (%d,%d) testing users/pairs.\n", testUser, m_testPair);
	}
	
	// we construct user pair as training instances and input into svm for training
	public void trainSVM(){
		// train svm model
			
		ArrayList<Feature[]> fvsArr = new ArrayList<Feature[]>();
		ArrayList<Double> ysArr = new ArrayList<Double>();
		constructXsYs(fvsArr, ysArr);

		int trainSize = ysArr.size();
		Feature[][] fvs = new Feature[trainSize][];
		double[] ys = new double[trainSize];
		for(int i=0; i<fvsArr.size(); i++){
			fvs[i] = fvsArr.get(i);
			ys[i] = ysArr.get(i);
		}
			
		Problem libProblem = new Problem();
		libProblem.l = trainSize;
		libProblem.x = fvs;
		libProblem.y = ys;
		libProblem.n = m_kBar*m_kBar+1;
		libProblem.bias = 1;// bias term in liblinear.

		SolverType type = SolverType.L2R_L1LOSS_SVC_DUAL;// solver type: prime
		m_libModel = Linear.train(libProblem, new Parameter(type, m_C, SVM.EPS));

	}
	
	// calculate the similarity between two users based on trained sv
	@Override
	protected double calcSimilarity(_MMBAdaptStruct ui, _MMBAdaptStruct uj){
		return Linear.predictValue(m_libModel, constructOneX(ui, uj), 1);
	}
	
	protected void constructXsYs(ArrayList<Feature[]> fvsArr, ArrayList<Double> ysArr){
		// construct training instances
		double eij = 0;
		_MMBAdaptStruct ui, uj;
		for(int i=0; i<m_testSize; i++){
			ui = m_testSet.get(i);
			for(int j=0; j<m_testSize; j++){
				if(j == i) continue;
				uj = m_testSet.get(j);
				eij = ui.getUser().hasFriend(uj.getUserID()) ? 1 : 0;
				// we only pick some of the zero edges for training
				if(eij == 1 || (eij == 0 && Math.random() <= m_rho)){
					fvsArr.add(constructOneX(ui, uj));
					ysArr.add(eij);
				}
			}
		}
	}
	
	protected Feature[] constructOneX(_MMBAdaptStruct ui, _MMBAdaptStruct uj){
		ArrayList<Feature> fv = new ArrayList<Feature>();
		double[] mixI = ui.getMixture(), mixJ = uj.getMixture();
		for(int k=0; k<mixI.length; k++){
			if(mixI[k] == 0) continue;
			for(int l=0; l<mixJ.length; l++){
				if(mixJ[l] == 0) continue;
				fv.add(new FeatureNode(k*m_kBar+l+1, mixI[k]*mixJ[l]));
			}
		}
		Feature[] x = new Feature[fv.size()];
		for(int i=0; i<fv.size(); i++){
			x[i] = fv.get(i);
		}
		return x;
	}

}