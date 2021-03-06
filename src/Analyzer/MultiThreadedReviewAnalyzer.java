package Analyzer;

import java.io.*;
import java.util.ArrayList;

import opennlp.tools.util.InvalidFormatException;
import json.JSONArray;
import json.JSONException;
import json.JSONObject;
import structures._Doc4ETBIR;
import structures._Review;


public class MultiThreadedReviewAnalyzer extends MultiThreadedUserAnalyzer {
	String source;
	
	public MultiThreadedReviewAnalyzer(String tokenModel, int classNo,
			String providedCV, int Ngram, int threshold, int numberOfCores,
			boolean b, String source) throws InvalidFormatException, FileNotFoundException,
			IOException {
		super(tokenModel, classNo, providedCV, Ngram, threshold, numberOfCores, b);
		this.source = source;
	}
	
	@Override
	protected void loadUser(String filename, int core) {
		if (filename.toLowerCase().endsWith(".json")) {
			String[] keys;
			if (source.equals("yelp"))
				keys = new String[]{"review_id", "text", "user_id", "business_id", "stars"};
			else
				keys = new String[]{"reviewText", "reviewerID", "asin", "overall"};

			JSONArray jarray = null;
			try {
				JSONObject json = LoadJSON(filename);
				jarray = json.getJSONArray("reviews");
			} catch (Exception e) {
				System.err.print("!FAIL to parse a json file...");
				return;
			}

			JSONObject obj;
			_Doc4ETBIR review;
			String name, text, productID, userID;
			int ylabel, reviewNum = 0;
			long timestamp = 0;
			for (int u = 0; u < jarray.length(); u++) {
				try {
					int index = 0;
					obj = jarray.getJSONObject(u);
					name = source.equals("yelp") ? obj.getString(keys[index++]) : String.valueOf(reviewNum++);
					text = obj.getString(keys[index++]).replaceAll("\n", " ").trim().replaceAll("\\s+", " ");
					userID = obj.getString(keys[index++]);
					productID = obj.getString(keys[index++]);
					ylabel = obj.getInt(keys[index]);
					review = new _Doc4ETBIR(m_corpus.getSize(), name, productID, userID, text, ylabel, timestamp);
					AnalyzeDoc(review, core);
				} catch (JSONException e) {
					System.out.println("!FAIL to parse a json object...");
				}
			}
		}else if(filename.toLowerCase().endsWith(".txt")){
			try {
				File file = new File(filename);
				BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
				String line;
				String userID = extractUserID(file.getName()); //UserId is contained in the filename.

				// Skip the first line since it is user name.
				reader.readLine();

				String productID, source, category="";
				ArrayList<_Review> reviews = new ArrayList<>();

				_Review review;
				int ylabel, reviewNum = 0;
				long timestamp=0;
				while((line = reader.readLine()) != null){
					productID = line;
					source = reader.readLine(); // review content
					category = reader.readLine(); // review category
					ylabel = Integer.valueOf(reader.readLine());
					timestamp = Long.valueOf(reader.readLine());

					// Construct the new review.
					if(ylabel != 3){
						ylabel = (ylabel >= 4) ? 1:0;
						review = new _Doc4ETBIR(m_corpus.getSize(), category, productID, userID, source, ylabel, timestamp);
						if(AnalyzeDoc(review,core)){ //Create the sparse vector for the review.
							reviews.add(review);
						}
					}
				}

				reader.close();
			} catch(IOException e){
				e.printStackTrace();
			}
		}
	}

}
