package topicmodels;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

import structures._ChildDoc;
import structures._Corpus;
import structures._Doc;
import structures._ParentDoc;
import structures._SparseFeature;
import structures._Stn;
import structures._Word;

public class correspondence_LDA_Gibbs extends LDA_Gibbs_Debug{
	boolean m_statisticsNormalized = false;//a warning sign of normalizing statistics before collecting new ones
	double[] m_topicProbCache;
	
	public correspondence_LDA_Gibbs(int number_of_iteration, double converge, double beta, _Corpus c, double lambda,
			int number_of_topics, double alpha, double burnIn, int lag, double ksi, double tau){
		super(number_of_iteration, converge, beta, c, lambda, number_of_topics, alpha, burnIn, lag, ksi, tau);
	
		m_topicProbCache = new double[number_of_topics];
	}
	
	@Override
	protected void initialize_probability(Collection<_Doc> collection){
		for(int i=0; i<number_of_topics; i++)
			Arrays.fill(word_topic_sstat[i], d_beta);
		Arrays.fill(m_sstat, d_beta*vocabulary_size);
		
		for(_Doc d: collection){
			if(d instanceof _ParentDoc){
				for(_Stn stnObj: d.getSentences()){
					stnObj.setTopicsVct(number_of_topics);	
				}
				d.setTopics4Gibbs(number_of_topics, 0);

			}
			else if(d instanceof _ChildDoc){
				((_ChildDoc) d).setTopics4Gibbs_LDA(number_of_topics, 0);
			}
			
			for(_Word w:d.getWords()){
				word_topic_sstat[w.getTopic()][w.getIndex()] ++;
				m_sstat[w.getTopic()] ++;
			}
		
		}
		
		imposePrior();
		
		m_statisticsNormalized = false;
	}
	
	public String toString(){
		return String.format("correspondence LDA [k:%d, alpha:%.2f, beta:%.2f, Gibbs Sampling]",
				number_of_topics, d_alpha, d_beta);
	}
	
	public double calculate_E_step(_Doc d){
		d.permutation();
		
		if(d instanceof _ParentDoc)
			sampleInParentDoc((_ParentDoc)d);
		else if(d instanceof _ChildDoc)
			sampleInChildDoc((_ChildDoc)d);
		
		return 0;
	}
	
	public void sampleInParentDoc(_ParentDoc d){
		int wid, tid;
		double normalizedProb;
		
		for(_Word w:d.getWords()){
			wid = w.getIndex();
			tid = w.getTopic();
			
			d.m_sstat[tid] --;
			if(m_collectCorpusStats){
				word_topic_sstat[tid][wid] --;
				m_sstat[tid] --;
			}
			
			normalizedProb = 0;
			for(tid=0; tid<number_of_topics; tid++){
				double pWordTopic = parentWordByTopicProb(tid, wid);
				double pTopicPDoc = parentTopicInDocProb(tid, d);
				double pTopicCDoc = parentChildInfluenceProb(tid, d);
				
				m_topicProbCache[tid] = pWordTopic*pTopicPDoc*pTopicCDoc;
				normalizedProb += m_topicProbCache[tid];
			}
			
			normalizedProb *= m_rand.nextDouble();
			for(tid=0; tid<number_of_topics; tid++){
				normalizedProb -= m_topicProbCache[tid];
				if(normalizedProb<0)
					break;
			}
			
			if(tid == number_of_topics)
				tid --;
			
			w.setTopic(tid);
			d.m_sstat[tid] ++;
			if(m_collectCorpusStats){
				word_topic_sstat[tid][wid] ++;
				m_sstat[tid] ++;
			}
			
		}
	}
	
	protected double parentWordByTopicProb(int tid, int wid){
		return word_topic_sstat[tid][wid]/m_sstat[tid];
	}
	
	protected double parentTopicInDocProb(int tid, _ParentDoc d){
		return (d_alpha+d.m_sstat[tid]);
	}
	
	protected double parentChildInfluenceProb(int tid, _ParentDoc d){
		double term = 1;
		
		if(tid==0)
			return term;
		
		for(_ChildDoc cDoc: d.m_childDocs){
			for(_Word w:cDoc.getWords()){
				int tempTid = w.getTopic();
				if(tempTid == tid)
					term *= (d.m_sstat[tempTid]+1)/(d.m_sstat[tempTid]+1e-10);
				else 
					if(tempTid == 0)
						term *= (d.m_sstat[tempTid]+1e-10)/(d.m_sstat[tempTid]+1);
				
			}
		}
		
		if(term==0){
			term += 1e-10;
		}
		
		return term;
	}
	
	protected void sampleInChildDoc(_ChildDoc d){
		int wid, tid;
		double normalizedProb = 0;
		
		for(_Word w: d.getWords()){
			wid = w.getIndex();
			tid = w.getTopic();
			
			d.m_sstat[tid]--;
			if(m_collectCorpusStats){
				word_topic_sstat[tid][wid] --;
				m_sstat[tid] --;
			}
			
			normalizedProb = 0;
			for(tid=0; tid<number_of_topics; tid++){
				double pWordTopic = childWordByTopicProb(tid, wid);
				double pTopicDoc = childTopicInDoc(tid, d);
				
				m_topicProbCache[tid] = pWordTopic*pTopicDoc;
				normalizedProb += m_topicProbCache[tid];
			}
			
			normalizedProb *= m_rand.nextDouble();
			for(tid=0; tid<number_of_topics; tid++){
				normalizedProb -= m_topicProbCache[tid];
				if(normalizedProb<0)
					break;
			}
			
			if(tid == number_of_topics)
				tid --;
			
			w.setTopic(tid);
			d.m_sstat[tid] ++;
			if(m_collectCorpusStats){
				word_topic_sstat[tid][wid] ++;
				m_sstat[tid] ++;
			}
		}
	}
	
	protected double childWordByTopicProb(int tid, int wid){
		return word_topic_sstat[tid][wid]/m_sstat[tid];
	}
	
	protected double childTopicInDoc(int tid, _ChildDoc d){
		// _ParentDoc tempParentDoc = d.m_parentDoc;
		// double term = tempParentDoc.m_sstat[tid];
		double term = d.m_sstat[tid] + 1e-10;

		return term;
	}
	
	public void calculate_M_step(int iter){
		if(iter>m_burnIn && iter%m_lag==0){
			if(m_statisticsNormalized){
				System.err.println("The statistics collector has been normlaized before, cannot further accumulate the samples!");
				System.exit(-1);
			}
			
			for(int i=0; i<number_of_topics; i++){
				for(int v=0; v<vocabulary_size; v++){
					topic_term_probabilty[i][v] += word_topic_sstat[i][v];
				}
			}
			
			for(_Doc d:m_trainSet){
				if(d instanceof _ParentDoc)
					collectParentStats((_ParentDoc)d);
				else if(d instanceof _ChildDoc)
					collectChildStats((_ChildDoc)d);
					
			}
		}
	}
	
	public void collectParentStats(_ParentDoc d){
		for(int k=0; k<number_of_topics; k++)
			d.m_topics[k] += d.m_sstat[k] + d_alpha;
		d.collectTopicWordStat();		
	}
	
	public void collectChildStats(_ChildDoc d){
		for(int k=0; k<number_of_topics; k++)
			d.m_topics[k] += d.m_sstat[k];
		
		_ParentDoc pDoc = d.m_parentDoc;
//		rankStn4Child(d, pDoc);
	}
	
	@Override
	public double inference(_Doc pDoc){
		ArrayList<_Doc> sampleTestSet = new ArrayList<_Doc>();
		
		initTest(sampleTestSet, pDoc);
	
		double logLikelihood = 0.0, count = 0;
		int  iter = 0;
		do {
			int t;
			_Doc tmpDoc;
			for(int i=sampleTestSet.size()-1; i>1; i--) {
				t = m_rand.nextInt(i);
				
				tmpDoc = sampleTestSet.get(i);
				sampleTestSet.set(i, sampleTestSet.get(t));
				sampleTestSet.set(t, tmpDoc);			
			}
			
			for(_Doc doc: sampleTestSet)
				calculate_E_step(doc);
			
			if (iter>m_burnIn && iter%m_lag==0){
				double tempLogLikelihood = 0;
				for(_Doc doc: sampleTestSet){
					if(doc instanceof _ParentDoc){
						collectParentStats((_ParentDoc) doc);
					}
					else if(doc instanceof _ChildDoc){
						collectChildStats((_ChildDoc) doc);
					}
					
				}

			}
		} while (++iter<this.number_of_iteration);

		for(_Doc doc: sampleTestSet){
			estThetaInDoc(doc);
		}
		
		return logLikelihood;
	}

	protected void initTest(ArrayList<_Doc> sampleTestSet, _Doc d){
		_ParentDoc pDoc = (_ParentDoc)d;
		for(_Stn stnObj: pDoc.getSentences()){
			stnObj.setTopicsVct(number_of_topics);
		}
		pDoc.setTopics4Gibbs(number_of_topics, 0);		
		sampleTestSet.add(pDoc);
		
		for(_ChildDoc cDoc: pDoc.m_childDocs){
			cDoc.setTopics4Gibbs_LDA(number_of_topics, 0);
			sampleTestSet.add(cDoc);
		}
	}

	public double logLikelihoodByIntegrateTopics(_ParentDoc d){
		double docLogLikelihood = 0;
		
		_SparseFeature[] fv = d.getSparse();
		
		for(int j=0; j<fv.length; j++){
			int wid = fv[j].getIndex();
			double value = fv[j].getValue();
			
			double wordLogLikelihood = 0;
			for(int k=0; k<number_of_topics; k++){
				double wordPerTopicLikelihood = parentWordByTopicProb(k, wid)*parentTopicInDocProb(k, d)/(d_alpha*number_of_topics+d.getTotalDocLength());
				wordLogLikelihood += wordPerTopicLikelihood;
			}
			
			if(Math.abs(wordLogLikelihood)<1e-10){
				System.out.println("wordLogLikelihood\t"+wordLogLikelihood);
				wordLogLikelihood += 1e-10;
			}
			
			wordLogLikelihood = Math.log(wordLogLikelihood);
			
			docLogLikelihood += value*wordLogLikelihood;
		}
		
		return docLogLikelihood;
	}
	
	public double logLikelihoodByIntegrateTopics(_ChildDoc d){
		double docLogLikelihood = 0;
		
		_SparseFeature[] fv = d.getSparse();
		for(int i=0; i<fv.length; i++){
			int wid = fv[i].getIndex();
			double value = fv[i].getValue();
			double wordLogLikelihood = 0;
			for(int k=0; k<number_of_topics; k++){
				double wordPerTopicLikelihood = childWordByTopicProb(k, wid)
						* (d.m_sstat[k] + 1e-10)
						/ (number_of_topics * 1e-10 + d.getTotalDocLength());
				wordLogLikelihood += wordPerTopicLikelihood;
			}
			
			if(wordLogLikelihood< 1e-10){
				wordLogLikelihood += 1e-10;
				System.out.println("small likelihood in child");
			}
			
			wordLogLikelihood = Math.log(wordLogLikelihood);
			
			docLogLikelihood += value*wordLogLikelihood;
		}
		
		return docLogLikelihood;
	}
	
	//stn is a query, retrieve comment by likelihood
	protected HashMap<String, Double> rankChild4StnByLikelihood(_Stn stnObj, _ParentDoc pDoc){
	
		HashMap<String, Double>childLikelihoodMap = new HashMap<String, Double>();

		for(_ChildDoc cDoc:pDoc.m_childDocs){
			int cDocLen = cDoc.getTotalDocLength();
			
			double stnLogLikelihood = 0;
			for(_Word w: stnObj.getWords()){
				double wordLikelihood = 0;
				int wid = w.getIndex();
			
				for(int k=0; k<number_of_topics; k++){
					wordLikelihood += (word_topic_sstat[k][wid]/m_sstat[k])*((cDoc.m_sstat[k]+d_alpha)/(d_alpha*number_of_topics+cDocLen));
				}
				
				stnLogLikelihood += Math.log(wordLikelihood);
			}
			childLikelihoodMap.put(cDoc.getName(), stnLogLikelihood);
		}
		
		return childLikelihoodMap;
	}
	
	
}

