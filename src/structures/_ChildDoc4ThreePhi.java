package structures;

import java.util.Arrays;

import utils.Utils;

public class _ChildDoc4ThreePhi extends _ChildDoc{

	public double m_childWordSstat;
	double m_beta; 
	
	public _ChildDoc4ThreePhi(int ID, String name, String title, String source, int ylabel) {
		super(ID, name, title, source, ylabel);
		
		// TODO Auto-generated constructor stub
	}

	public void createXSpace(int k, int gammaSize, int vocalSize, double beta) {
		m_beta = beta*0.0001; 
		
		m_xTopicSstat = new double[gammaSize][];
		m_xTopics = new double[gammaSize][];

		m_xTopicSstat[0] = new double[k];
		m_xTopics[0] = new double[k];

		m_xTopicSstat[1] = new double[1];
		m_xTopics[1] = new double[1];

		m_xTopicSstat[2] = new double[vocalSize];
		m_xTopics[2] = new double[vocalSize];

		m_xSstat = new double[gammaSize];
		m_xProportion = new double[gammaSize];

		Arrays.fill(m_xTopicSstat[0], 0);
		Arrays.fill(m_xTopics[0], 0);
		
		Arrays.fill(m_xTopicSstat[1], 0);
		Arrays.fill(m_xTopics[1], 0);
		
		Arrays.fill(m_xTopicSstat[2], m_beta);
		Arrays.fill(m_xTopics[2], 0);
		
		Arrays.fill(m_xSstat, 0);
		Arrays.fill(m_xProportion, 0);
		
		m_childWordSstat = m_beta*vocalSize;
	}
	
	public void setTopics4Gibbs(int k, double alpha){
		createSpace(k + 1, alpha);
		
		int wIndex = 0, wid, tid, xid, gammaSize = m_xSstat.length;
		tid = 0;
		for(_SparseFeature fv:m_x_sparse){
			wid = fv.getIndex();
			for(int j=0; j<fv.getValue(); j++){
				xid = m_rand.nextInt(gammaSize);
				if(xid == 0){
					tid = m_rand.nextInt(k);
					m_xTopicSstat[xid][tid]++;
					m_xSstat[xid]++;
				}else if(xid==1){
					tid = k;
					m_xTopicSstat[xid][0]++;
					m_xSstat[xid]++;
				}else if(xid==2){
					tid = k + 1;
					m_xTopicSstat[xid][wid]++;
					m_xSstat[xid]++;
					m_childWordSstat ++;
				}
				
				m_words[wIndex] = new _Word(wid, tid, xid);
				
				wIndex ++;
			}
		}
	}
	
	public void estGlobalLocalTheta(){
		Utils.L1Normalization(m_topics);

		for (int i = 0; i < m_topics.length; i++) {
			if (Double.isNaN(m_topics[i]))
				System.out.println("topic proportion \t" + m_topics[i]);
		}

		Utils.L1Normalization(m_xProportion);
		Utils.L1Normalization(m_xTopics[2]);
	}
	
}
