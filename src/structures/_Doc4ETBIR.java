package structures;

import java.util.Arrays;

/**
 * Created by lulin on 3/28/18.
 */
public class _Doc4ETBIR extends _Doc{

    public double[] m_mu; // mean vector \mu in variational inference p(\theta|\mu,\Sigma)
    public double[] m_Sigma; // diagonal covariance matrix \Sigma in variational inference p(\theta|\mu,\Sigma)
    public double[] m_sigmaSqrt; // square root of diagonal elements in Sigma
    public double m_zeta; //Taylor expansion parameter \zeta related to p(\theta|\mu,\Sigma)
    public double[] m_phiStat;// temporally store \sum_w c(w)p(z|w)
    
    public _Doc4ETBIR(int ID, String name, String prodID, String title, String source, int ylabel, long timeStamp){
        super(ID,  name,  prodID,  title,  source,  ylabel,  timeStamp);
    }
    
    //create necessary structure for variational inference    
  	public void setTopics4Variational(int k, double alpha, double mu, double sigma) {
    	super.setTopics4Variational(k, alpha);
  		
    	m_zeta = 1.0;
        m_mu = new double[k];
        m_Sigma = new double[k];
        m_sigmaSqrt = new double[k];
        m_phiStat = new double[k];
        
        Arrays.fill(m_mu, mu);
        Arrays.fill(m_Sigma, sigma);
  	}
}