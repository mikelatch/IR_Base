package Classifier.supervised;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

import Classifier.supervised.liblinear.Feature;
import Classifier.supervised.liblinear.Linear;
import Classifier.supervised.liblinear.Parameter;
import Classifier.supervised.liblinear.Problem;
import Classifier.supervised.modelAdaptation._AdaptStruct;
import structures._Doc;
import structures._Review;
import structures._Doc.rType;

public class GlobalSVM extends IndividualSVM{

	public GlobalSVM(int classNo, int featureSize) {
		super(classNo, featureSize);
		m_pWeights = new double[m_featureSize+1];
	}
	
	@Override
	public String toString() {
		return String.format("Global-SVM[C:%.3f,bias:%b]", m_C, m_bias);
	}
	
	@Override
	public double train() {
		init();
		
		//Transfer all user reviews to instances recognized by SVM, indexed by users.
		int trainSize = 0, validUserIndex = 0;
		ArrayList<Feature []> fvs = new ArrayList<Feature []>();
		ArrayList<Double> ys = new ArrayList<Double>();		
		
		//Two for loop to access the reviews, indexed by users.
		ArrayList<_Review> reviews;
		for(_AdaptStruct user:m_userList){
			reviews = user.getReviews();		
			boolean validUser = false;
			for(_Review r:reviews) {				
				if (r.getType() == rType.ADAPTATION) {//we will only use the adaptation data for this purpose
					fvs.add(createLibLinearFV(r, validUserIndex));
					ys.add(new Double(r.getYLabel()));
					trainSize ++;
					validUser = true;
				}
			}
			if (validUser)
				validUserIndex ++;
		}
		// Train individual model for each user.
		Problem libProblem = new Problem();
		libProblem.l = trainSize;		
		libProblem.x = new Feature[trainSize][];
		libProblem.y = new double[trainSize];
		for(int i=0; i<trainSize; i++) {
			libProblem.x[i] = fvs.get(i);
			libProblem.y[i] = ys.get(i);
		}
		
		if (m_bias) {
			libProblem.n = m_featureSize + 1; // including bias term; global model + user models
			libProblem.bias = 1;// bias term in liblinear.
		} else {
			libProblem.n = m_featureSize;
			libProblem.bias = -1;// no bias term in liblinear.
		}
	
		m_libModel = Linear.train(libProblem, new Parameter(m_solverType, m_C, SVM.EPS));
		setPersonalizedModel();
		calcPredValue();
		return 0;
	}

	public double train(ArrayList<_AdaptStruct> userList) {
		m_userSize = 0;
		for(_AdaptStruct user: userList){			
			if (user.getAdaptationSize()>0) 				
				m_userSize ++;	
			user.getPerfStat().clear(); // clear accumulate performance statistics
		}
		
		//Transfer all user reviews to instances recognized by SVM, indexed by users.
		int trainSize = 0, validUserIndex = 0;
		ArrayList<Feature []> fvs = new ArrayList<Feature []>();
		ArrayList<Double> ys = new ArrayList<Double>();		
		
		//Two for loop to access the reviews, indexed by users.
		ArrayList<_Review> reviews;
		for(_AdaptStruct user:userList){
			reviews = user.getReviews();		
			boolean validUser = false;
			for(_Review r:reviews) {				
				if (r.getType() == rType.ADAPTATION) {//we will only use the adaptation data for this purpose
					fvs.add(createLibLinearFV(r, validUserIndex));
					ys.add(new Double(r.getYLabel()));
					trainSize ++;
					validUser = true;
				}
			}
			if (validUser)
				validUserIndex ++;
		}
		// Train individual model for each user.
		Problem libProblem = new Problem();
		libProblem.l = trainSize;		
		libProblem.x = new Feature[trainSize][];
		libProblem.y = new double[trainSize];
		for(int i=0; i<trainSize; i++) {
			libProblem.x[i] = fvs.get(i);
			libProblem.y[i] = ys.get(i);
		}
		
		if (m_bias) {
			libProblem.n = m_featureSize + 1; // including bias term; global model + user models
			libProblem.bias = 1;// bias term in liblinear.
		} else {
			libProblem.n = m_featureSize;
			libProblem.bias = -1;// no bias term in liblinear.
		}
	
		m_libModel = Linear.train(libProblem, new Parameter(m_solverType, m_C, SVM.EPS));
		setPersonalizedModel(userList);
		calcPredValue(userList);
		return 0;
	}

	/////////// Used in mixed models.////////////
	protected void setPersonalizedModel(ArrayList<_AdaptStruct> userList) {
		for(_AdaptStruct user: userList)
			super.setPersonalizedModel(user);
	}
	
	public void calcPredValue(ArrayList<_AdaptStruct> userList){
		for(_AdaptStruct u: userList){
			for(_Review r: u.getReviews())
				r.setPredValue(predictDouble(r));
		}
	}
	//////////////////////////////////////////
	
	public void calcPredValue(){
		for(_AdaptStruct u: m_userList){
			for(_Review r: u.getReviews())
				r.setPredValue(predictDouble(r));
		}
	}
	
	public double predictDouble(_Doc doc){
		return Linear.predict(m_libModel, Utils.createLibLinearFV(doc, m_featureSize));

	}
	
	@Override
	protected void setPersonalizedModel() {
		for(_AdaptStruct user: m_userList)
			super.setPersonalizedModel(user);
	}
	
	public void saveSupModel(String filename){
		double[] weights = m_libModel.getWeights();
		int class0 = m_libModel.getLabels()[0];
		double sign = class0 > 0 ? 1 : -1;
		try{
			PrintWriter writer = new PrintWriter(new File(filename));
			if(m_bias)
				writer.write(sign*weights[m_featureSize]+"\n");
			else
				writer.write(0);
				
			for(int i=0; i<m_featureSize; i++)
					writer.write(weights[i]+"\n");
			
			writer.close();
		} catch(IOException e){
			e.printStackTrace();
		}
	}
}
