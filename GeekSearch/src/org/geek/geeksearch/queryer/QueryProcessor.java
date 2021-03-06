package org.geek.geeksearch.queryer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.ansj.domain.Term;
import org.apache.catalina.tribes.group.interceptors.TwoPhaseCommitInterceptor.MapEntry;
import org.geek.geeksearch.configure.Configuration;
import org.geek.geeksearch.indexer.IndexGenerator;
import org.geek.geeksearch.indexer.Tokenizer;
import org.geek.geeksearch.model.InvertedIndex;
import org.geek.geeksearch.model.PageInfo;
import org.geek.geeksearch.model.TermStat;
import org.geek.geeksearch.util.DBOperator;

import sun.launcher.resources.launcher;

public class QueryProcessor {
	private HashMap<String, Long> termIDsMap = new HashMap<>(); //词项-词项ID 映射表
	private Map<Long,InvertedIndex> invIdxMap = new HashMap<>(); //倒排索引表
	private HashMap<String,Integer> queryHistory = new HashMap<>(); //检索历史，到一定size写入数据库
	private int topK = 100; //设置胜者表的topK
	
	private final Configuration config;
	private final Tokenizer tokenizer;
	private final DBOperator dbOperator; 
	
	public QueryProcessor() {
		this.config = new Configuration();
		this.dbOperator = new DBOperator(config);
		this.tokenizer = new Tokenizer();
		setTopK(config);
		loadInvertedIndex();
		loadTermsIndex();
	}
	
	/**
	 * 检索入口
	 * 返回值为已排序并聚类后的相关page
	 * 第二层链表表示同一类page，如果
	 * 
	 */
	public List<List<PageInfo>> doQuery(String query) {
		// 分词 
		List<Long> queryIDs = parseQuery(query);
		if (queryIDs == null || queryIDs.isEmpty()) {
			System.out.println("nothing to search!");
			return null;
		}
		
		// 获取已排序的相关网页及信息
		List<PageInfo> resultPages = getResultPages(queryIDs);
		if (resultPages == null || resultPages.isEmpty()) {
			System.out.println("nothing retrived for query: "+ query);
			return null;
		}
		
		// 聚类
		return PageCluster.doCluster(resultPages); 
		// snippet和快照在PageInfo.java中实现
	}
	
	/* 获取相关网页，并从数据库PagesIndex获取网页信息 */
	private List<PageInfo> getResultPages(List<Long> queryIDs) {
		List<PageInfo> resultPages = new ArrayList<>();
		
		List<Map.Entry<Long, TermStat>> relevantDocs = getRelevantDocs(queryIDs);
		if (relevantDocs == null || relevantDocs.isEmpty()) {
			System.out.println("no pages retrived");
			return null;
		}
		
		//计算相似度权重nnn.ntn
		for (Map.Entry<Long, TermStat> doc : relevantDocs) {
			System.out.println("doc: "+doc.getKey()+"");
			for (long term : queryIDs) {
				TermStat stat = invIdxMap.get(term).getStatsMap().get(doc.getKey());
				if (stat == null) {
					System.out.println("can not find doc stat in term: "+term);
				}
				doc.getValue().addWeight(stat.getTfIdf());
				System.out.println("w["+term +", "+doc.getKey()
						+"]="+stat.getTfIdf());//
			}
			System.out.println("weight["+doc.getKey()+"]="+doc.getValue().getWeight());//
		}
		
		//relevantDocs根据相似度权重降序排列
		Collections.sort(relevantDocs, new Comparator<Map.Entry<Long, TermStat>>() {
			public int compare(Map.Entry<Long, TermStat> o1, Map.Entry<Long, TermStat> o2) {
				return o2.getValue().getWeight() > o1.getValue().getWeight() ? 1 : -1;
			}
		});
		
		//从PagesIndex获取PageInfo
		PageInfo page;
		for (Map.Entry<Long, TermStat> doc : relevantDocs) {
			page = new PageInfo(doc.getKey());
			if (!page.loadInfo(dbOperator)) {
				System.out.println("no page info of "+doc.getKey());
				continue;
			}
			resultPages.add(page);
			System.out.println("\nretrived page: "+ doc.getKey());
		}
		return resultPages;
	}
	
	/* 获取各个词项的TopK篇文档，求并集,此处尚未考虑只包含部分检索词的文档,返回的文档都包含所有检索词*/
	private List<Map.Entry<Long, TermStat>> getRelevantDocs(List<Long> queryIDs) {
		Map<Long, TermStat> mergedResult = new TreeMap<>();
		Map<Long, TermStat> tmpDocs = new TreeMap<>();		
//		List<Long> sortedQIDs = sortQueryIDs(queryIDs);
		
		//对每个词项id获取topK文档
		mergedResult = invIdxMap.get(queryIDs.get(0)).getTopKDocs();
		for (int k = 1; k < queryIDs.size(); k++) {
			tmpDocs = invIdxMap.get(queryIDs.get(k)).getTopKDocs();
			//将该词项id的topK文档与上一次merge结果进行merge
			Iterator<Map.Entry<Long, TermStat>> iter = mergedResult.entrySet().iterator();
			while (iter.hasNext()) {
				Map.Entry<Long, TermStat> entry = iter.next();
				long docId = entry.getKey();
				if (!tmpDocs.containsKey(docId)) {
					iter.remove();
					System.out.println("remove "+docId);
					continue;
				}
				System.out.println("remain "+docId);
			}
		}
		return new ArrayList<Map.Entry<Long,TermStat>>(mergedResult.entrySet());
	}
	
	/*按照检索词项的相关文档规模排序，便于merge*/
//	private List<Long> sortQueryIDs(List<Long> queryIDs) {
//		List<Long> sortedQIDs = new ArrayList<>();
//		List<Integer> docsSize = new ArrayList<>();
//		for (long id : queryIDs) {
//			docsSize.add(invIdxMap.get(id).getTopKDocs().size());
//		}
//		long min = -1;
//		for (int i = 0; i < docsSize.size(); i++) {
//			min = docsSize.get(i);
//			for (int j = i+1; j < docsSize.size(); j++) {
//				if (docs) {
//					
//				}
//			}
//		}
//
//		return sortedQIDs;
//	}
	
	/* query解析 */
	private List<Long> parseQuery(String query) {
		// 分词
		List<String> queryTerms = tokenizer.doQueryTokenise(query);
//		List<String> queryTerms = new ArrayList<>();// just for test
//		queryTerms.add("中");
//		queryTerms.add("詹姆斯");
		if (queryTerms == null || queryTerms.isEmpty()) {
			return null;
		}
		// 映射成ID
		List<Long> queryIDs = new ArrayList<>();
		for (String term : queryTerms) {
			if (term == null || term.isEmpty()) {
				continue;
			}
			long id = fetchTermID(term);
			if (id < 0) {
				//跳过索引库中没有的词项
				continue;
			}
			queryIDs.add(id);
		}
		return queryIDs;
	}
	
	/* 从TermsIndex获取termID */
	private long fetchTermID(String term) {
		String sql = " SELECT * FROM TERMSINDEX WHERE term='"+term+"' ";
		ResultSet rSet = dbOperator.executeQuery(sql);
		if (rSet == null) {
			System.out.println("can not find term: "+term);
			return -1;
		}
		long termID = -1;
		try {
			while (rSet.next()) {
				termID = rSet.getLong("TermID");
				break;				
			}			
		} catch (SQLException e) {
			System.out.println("can not find term: "+term);
			return -1;
		}
		return termID;
	}

	/* 加载 InvertedIndex 表 */
	private void loadInvertedIndex() {
		String sql = " SELECT * FROM INVERTEDINDEX ";
		ResultSet rSet = dbOperator.executeQuery(sql);
		if (rSet == null) {
			System.err.println("load nothing from table InvertedIndex!");
			return;
		}		
		InvertedIndex invIdx;
		long termID = -1;
		String docIDs = "";
		try {
			while (rSet.next()) {
				termID = rSet.getLong("TermID");
				docIDs = rSet.getString("DocumentIDs");
				if (docIDs == null || docIDs.isEmpty() || termID < 0) {
					continue;
				}
				invIdx = new InvertedIndex(termID);
				invIdx.parseIndex(docIDs, topK);
				invIdxMap.put(termID, invIdx);
			}
		} catch (SQLException e) {
			System.err.println("error occurs while loading termID: "+termID);
			e.printStackTrace();
		}
//		InvertedIndex.addAll2DB(invIdxMap, dbOperator, 3); //just for test
	}

	/* 加载 TermsIndex 表 */
	private void loadTermsIndex() {
		String sql = " SELECT * FROM TERMSINDEX ";
		ResultSet rSet = dbOperator.executeQuery(sql);
		if (rSet == null) {
			System.err.println("load nothing from table TermsIndex!");
			return;
		}		
		String term = "";
		long id = -1;
		try {
			while (rSet.next()) {
				term = rSet.getString("Term");
				id = rSet.getLong("TermID");
				if (term == null || term.isEmpty() || id < 0) {
					continue;
				}
				termIDsMap.put(term, id);
//				System.out.println(id+" = "+term);//
			}
		} catch (SQLException e) {
			System.err.println("error occurs while loading term: "+term);
			e.printStackTrace();
		}
	}
	
	/* 设置胜者表的topK */
	private void setTopK(Configuration config) {
		int tmp;
		try {
			tmp = Integer.parseInt(config.getValue("ChampionTopK"));
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		topK = tmp;		
	}
	
	/* just for test */
	public static void main(String[] args) {
		//重新建立索引
//		IndexGenerator generator = new IndexGenerator();
//		generator.createIndexes();
		QueryProcessor queryProc = new QueryProcessor();
		
		List<List<PageInfo>> result = queryProc.doQuery("中");//中 詹姆斯
		
		if (result == null) {
			System.out.println("sorry, 找不到相关页面");
		}
		for (List<PageInfo> set : result) {
			System.out.println("以下新闻为一类：");
			for (PageInfo page : set) {
				System.out.println(page.getUrl()+"\n标题："+page.getTitle());
			}
		}
	}
}
