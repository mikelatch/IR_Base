package Analyzer;

import structures.*;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class BipartiteAnalyzer {
    int m_k;
    _Corpus m_corpus;
    ArrayList<_Doc> m_trainSet;
    ArrayList<_Doc> m_testSet;

    protected List<_User> m_users;
    protected List<_Product> m_items;

    protected HashMap<String, Integer> m_usersIndex; //(userID, index in m_users)
    protected HashMap<String, Integer> m_itemsIndex; //(itemID, index in m_items)
    protected HashMap<String, Integer> m_reviewIndex; //(itemIndex_userIndex, index in m_corpus.m_collection)

    protected HashMap<Integer, ArrayList<Integer>>  m_mapByUser; //adjacent list for user, controlled by m_testFlag.
    protected HashMap<Integer, ArrayList<Integer>> m_mapByItem;
    protected HashMap<Integer, ArrayList<Integer>> m_mapByUser_test; //test
    protected HashMap<Integer, ArrayList<Integer>> m_mapByItem_test;

    public BipartiteAnalyzer(_Corpus corpus){
        this.m_corpus = corpus;
        m_users = new ArrayList<>();
        m_items = new ArrayList<>();
        m_usersIndex = new HashMap<>();
        m_itemsIndex = new HashMap<>();
        m_reviewIndex = new HashMap<>();
        m_mapByUser = new HashMap<>();
        m_mapByItem = new HashMap<>();
        m_mapByUser_test = new HashMap<>();
        m_mapByItem_test = new HashMap<>();
    }

    public void reset(){
        this.m_corpus.reset();
        m_users.clear();
        m_items.clear();
        m_usersIndex.clear();
        m_itemsIndex.clear();
        m_reviewIndex.clear();
        m_mapByUser.clear();
        m_mapByItem.clear();
        m_mapByUser_test.clear();
        m_mapByItem_test.clear();
    }

    public void analyzeCorpus(){
        System.out.print("[Info]Analzying corpus: ");

        m_users.clear();
        m_items.clear();
        m_usersIndex.clear();
        m_itemsIndex.clear();
        m_reviewIndex.clear();
        int u_index = 0, i_index = 0, size = m_corpus.getCollection().size();
        for(int d = 0; d < size; d++){
            _Review doc = (_Review) m_corpus.getCollection().get(d);
            String userID = doc.getUserID();
            String itemID = doc.getItemID();

            if(!m_usersIndex.containsKey(userID)){
                m_users.add(new _User4ETBIR(userID));
                m_usersIndex.put(userID, u_index);
                u_index++;
            }

            if(!m_itemsIndex.containsKey(itemID)){
                m_items.add(new _Product4ETBIR(itemID));
                m_itemsIndex.put(itemID, i_index);
                i_index++;
            }

            int uIdx = m_usersIndex.get(userID);
            int iIdx = m_itemsIndex.get(itemID);
            m_reviewIndex.put(iIdx + "_" + uIdx, d);

            //if ( (100 * d/size) % 10 == 0 )
            if(d % (size/10) == 0)
                System.out.print(".");//every 10%
        }

        System.out.format("-- Corpus: vocabulary size: %d, review size: %d, item size: %d, user size: %d\n",
                m_corpus.getFeatureSize(), size,  m_items.size(),  m_users.size());
    }

    public boolean analyzeBipartite(ArrayList<_Doc> docs, String source){
        HashMap<Integer, ArrayList<Integer>> mapByUser = source.equals("train")?m_mapByUser:m_mapByUser_test;
        HashMap<Integer, ArrayList<Integer>> mapByItem = source.equals("train")?m_mapByItem:m_mapByItem_test;

        System.out.format("[Info]Analying bipartie graph: \n");
        mapByItem.clear();
        mapByUser.clear();

        if(m_usersIndex == null){
            System.err.println("[Warning]Analyze Corpus first! Analyzing...");
            analyzeCorpus();
        }

        for (_Doc doc:docs){
            _Review d = (_Review) doc;
            int u_index = m_usersIndex.get(d.getUserID());
            int i_index = m_itemsIndex.get(d.getItemID());
            if(!mapByUser.containsKey(u_index)){
                mapByUser.put(u_index, new ArrayList<Integer>());
            }
            if(!mapByItem.containsKey(i_index)){
                mapByItem.put(i_index, new ArrayList<Integer>());
            }
            mapByUser.get(u_index).add(i_index);
            mapByItem.get(i_index).add(u_index);
        }
        System.out.format("-- %s graph: review size: %d, item size: %d, user size: %d\n",
                source, docs.size(), mapByItem.size(), mapByUser.size());
        if(source.equals("train") && (mapByItem.size()<m_items.size() || mapByUser.size()<m_users.size())){
            System.err.format("[Error]Poor split detected, train set does not contain all users/items.\n");
            return false;
        }
        return true;
    }

    public boolean splitCorpus(int k, String outFolder) {
        System.out.format("[Info]Splitting corpus into %d folds: ", m_k);

        this.m_k = k;
        m_trainSet = new ArrayList<>();
        m_testSet = new ArrayList<>();

        //split corpus with some cold start user and item


        m_corpus.shuffle(m_k);
        int[] masks = m_corpus.getMasks();
        ArrayList<_Doc> docs = m_corpus.getCollection();

        if(m_usersIndex == null){
            System.err.println("[Warning]Analysing corpus first! Analyzing...");
            analyzeCorpus();
        }

        //Use this loop to iterate all the ten folders, set the train set and test set.
        for (int i = 0; i < m_k; i++) {
            for (int j = 0; j < masks.length; j++) {
                if( masks[j]==i )
                    m_testSet.add(docs.get(j));
                else
                    m_trainSet.add(docs.get(j));
            }

            // generate bipartie for training set
            analyzeBipartite(m_trainSet, "train");
//            if(analyzeBipartite(m_trainSet, "train")==false){//false means poor split
//                System.err.format("[Error]Split corpus abort! Delete all generated cross validation folds.\n");
//                deleteDir(outFolder);
//                return false;
//            }

            // generate bipartie for testing set
            analyzeBipartite(m_testSet, "test");
            save2File(outFolder, String.valueOf(i));

            System.out.format("-- Fold No. %d: train size = %d, test size = %d\n", i, m_trainSet.size(), m_testSet.size());
            m_trainSet.clear();
            m_testSet.clear();
        }

        return true;
    }

    public boolean deleteDir(String outFolder){
        File dir = new File(outFolder);
        if(dir.isDirectory()){
            String[] children = dir.list();
            for(int i=0; i<children.length; i++){
                boolean success = deleteDir(children[i]);
                if(!success){
                    return false;
                }
            }
        }
        return dir.delete();
    }

    public void save2File(String outFolder, String mode){
        try {
            // save train
            String outTrain = outFolder + mode + "/";
            new File(outTrain).mkdirs();
            HashMap<Integer, ArrayList<Integer>> mapByUser = mode.equals("train")?m_mapByUser:m_mapByUser_test;
            for (int u_idx : mapByUser.keySet()) {
                _User user = m_users.get(u_idx);
                String userID = user.getUserID();
                FileWriter file = new FileWriter(outTrain + userID + ".txt");
                file.write(userID + "\n");
                for (int i_idx : mapByUser.get(u_idx)){
                    _Product item = m_items.get(i_idx);
                    _Doc doc =  m_corpus.getCollection().get(m_reviewIndex.get(i_idx + "_" + u_idx));
                    file.write(item.getID() + "\n");
                    file.write(doc.getSource() + "\n");
                    file.write("\n");
                    file.write(doc.getYLabel() + "\n");
                    file.write(doc.getTimeStamp() + "\n");
                }
                file.flush();
                file.close();
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public List<_User> getUsers(){ return this.m_users; }
    public List<_Product> getItems(){ return this.m_items; }
    public HashMap<String, Integer> getUsersIndex() { return this.m_usersIndex; }
    public HashMap<String, Integer> getItemsIndex() {return this.m_itemsIndex; }
    public HashMap<String, Integer> getReviewIndex() {return this.m_reviewIndex; }
    public HashMap<Integer, ArrayList<Integer>> getMapByUser() { return this.m_mapByUser; }
    public HashMap<Integer, ArrayList<Integer>> getMapByItem() { return this.m_mapByItem; }
    public HashMap<Integer, ArrayList<Integer>> getMapByUser_test(){ return this.m_mapByUser_test; }
    public HashMap<Integer, ArrayList<Integer>> getMapByItem_test() { return this.m_mapByItem_test; }
}
