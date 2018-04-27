package structures;

import java.util.Arrays;
import java.util.Random;

/**
 * Created by lulin on 3/29/18.
 */
public class _User4ETBIR extends _User{
    public double[][] m_nuP;
    public double[][][] m_SigmaP;

    public _User4ETBIR(String userID){
    	super(userID);
    }
    
    public void setTopics4Variational(int k, double nu, double sigma) {
    	m_nuP = new double[k][k];
        m_SigmaP = new double[k][k][k];

        long seed = k * 10000;
        for(int i = 0; i < k; i++){
            Random r = new Random(seed);
            for(int j = 0; j < k; j++){
                m_nuP[i][j] = r.nextDouble() + nu;
                for(int l = 0; l < k; l++){
                    m_SigmaP[i][j][l] = r.nextDouble() * 0.2 + sigma;
                }
                seed -= 30;
            }
        }
    }
}
