package com.aote.rs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.sql.Blob;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.log4j.Logger;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.collection.PersistentSet;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.proxy.map.MapProxy;
import org.hibernate.transform.ResultTransformer;
import org.hibernate.transform.Transformers;
import org.hibernate.type.DateType;
import org.hibernate.type.DoubleType;
import org.hibernate.type.LongType;
import org.hibernate.type.ManyToOneType;
import org.hibernate.type.SetType;
import org.hibernate.type.TimeType;
import org.hibernate.type.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.stereotype.Component;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import com.aote.expression.ExpressionGenerator;

@Path("db")
@Component
public class DBService {
	static Logger log = Logger.getLogger(DBService.class);

	@Autowired
	private HibernateTemplate hibernateTemplate;

	// 获取各种实体的属性信息
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public JSONObject metas() {
		JSONObject result = new JSONObject();
		// 获取所有实体
		Map<String, ClassMetadata> map = this.hibernateTemplate
				.getSessionFactory().getAllClassMetadata();
		for (Map.Entry<String, ClassMetadata> entry : map.entrySet()) {
			try {
				String key = entry.getKey();
				JSONObject attrs = new JSONObject();
				for (String name : entry.getValue().getPropertyNames()) {
					Type type = entry.getValue().getPropertyType(name);
					attrs.put(name, TypeToString(type));
				}
				// 添加id，id号没有当做属性获取
				String idName = entry.getValue().getIdentifierPropertyName();
				Type idType = entry.getValue().getIdentifierType();
				attrs.put(idName, TypeToString(idType));
				result.put(key, attrs);
			} catch (JSONException e) {
				throw new WebApplicationException(400);
			}
		}
		log.debug(result);
		return result;
	}

	private String TypeToString(Type type) {
		if (type instanceof ManyToOneType) {
			ManyToOneType t = (ManyToOneType) type;
			String entityName = t.getAssociatedEntityName();
			return entityName;
		} else if (type instanceof SetType) {
			String entityName = getCollectionEntityName((SetType) type);
			return entityName + "[]";
		} else {
			return type.getName();
		}
	}

	// 得到集合类型的关联实体类型
	private String getCollectionEntityName(SetType type) {
		SessionFactoryImplementor sf = (SessionFactoryImplementor) this.hibernateTemplate
				.getSessionFactory();
		String entityName = type.getAssociatedEntityName(sf);
		return entityName;
	}

	@GET
	@Path("{hql}")
	@Produces(MediaType.APPLICATION_JSON)
	public JSONArray query(@PathParam("hql") String query) {
		// %在路径中不能出现，把%改成了^
		// query = query.replaceAll("0x25", "%");
		// sql中有除号的时候替换
		query = query.replace("|", "/");
		log.debug(query);
		JSONArray array = new JSONArray();
		List<Object> list = this.hibernateTemplate.find(query);
		for (Object obj : list) {
			// 把单个map转换成JSON对象
			Map<String, Object> map = (Map<String, Object>) obj;
			JSONObject json = MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}

	@GET
	@Path("/agg/{hql}/{attrname}")
	@Produces(MediaType.APPLICATION_JSON)
	public JSONArray query(@PathParam("hql") String query, @PathParam("attrname") String names) {
		// sql中有除号的时候替换
		query = query.replace("|", "/");
		log.debug(query);
		JSONArray array = new JSONArray();
		List<Object> list = this.hibernateTemplate.find(query);
		for (Object obj : list) {
			//属性名为每一个计算项对应的名称
			String[] snames = names.split(",");
			//select多项时，后面的各项为第一项对象的计算属性
			Object[] objs = (Object[])obj;
			Map<String, Object> map = (Map<String, Object>) objs[0];
			for(int i = 1; i < objs.length; i++)
			{
				map.put(snames[i - 1], objs[i]);
			}
			JSONObject json = (JSONObject) new JsonTransfer().MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}

	@GET
	@Path("/one/{hql}")
	@Produces(MediaType.APPLICATION_JSON)
	public JSONObject queryOne(@PathParam("hql") String query) {
		// %在路径中不能出现，把%改成了^
		// query = query.replaceAll("\\^", "%");
		log.debug(query);
		JSONObject result = new JSONObject();
		List<Object> list = this.hibernateTemplate.find(query);
		if (list.size() != 1) {
			// 查询到多条数据，跑出异常
			throw new WebApplicationException(500);
		}
		// 把单个map转换成JSON对象
		Map<String, Object> map = (Map<String, Object>) list.get(0);
		result = MapToJson(map);
		log.debug(result.toString());
		return result;
	}

	@GET
	@Path("/sernum/{hql}/{attrname}")
	@Produces(MediaType.APPLICATION_JSON)
	public JSONObject getSerialNumber(@PathParam("hql") String query,
			@PathParam("attrname") String attrname) {
		log.debug(query);
		JSONObject result = new JSONObject();
		List<Object> list = this.hibernateTemplate.find(query);
		if (list.size() != 1) {
			// 查询到多条数据，跑出异常
			throw new WebApplicationException(500);
		}
		// 把单个map转换成JSON对象
		Map<String, Object> map = (Map<String, Object>) list.get(0);
		result = MapToJson(map);
		long attrVal = Long.parseLong(map.get(attrname).toString());
		map.put(attrname, attrVal + 1 + "");
		this.hibernateTemplate.update(map);
		log.debug(result.toString());
		return result;
	}

	@GET
	@Path("{hql}/{pageIndex}/{pageSize}")
	@Produces(MediaType.APPLICATION_JSON)
	// 获取一页数据
	public JSONArray query(@PathParam("hql") String query,
			@PathParam("pageSize") int pageSize,
			@PathParam("pageIndex") int pageIndex) {
		JSONArray array = new JSONArray();
		List list = this.hibernateTemplate.executeFind(new HibernateCall(query,
				pageIndex, pageSize));
		for (Object obj : list) {
			// 把单个map转换成JSON对象
			Map<String, Object> map = (Map<String, Object>) obj;
			JSONObject json = MapToJson(map);
			array.put(json);
		}
		return array;
	}

	// 执行分页查询
	public class HibernateCall implements HibernateCallback {
		String hql;
		int page;
		int rows;

		public HibernateCall(String hql, int page, int rows) {
			this.hql = hql;
			this.page = page;
			this.rows = rows;
		}

		public Object doInHibernate(Session session) {
			Query q = session.createQuery(hql);
			List result = q.setFirstResult(page * rows).setMaxResults(rows)
					.list();
			return result;
		}
	}

	// 把单个map转换成JSON对象
	private JSONObject MapToJson(Map<String, Object> map) {
		JSONObject json = new JSONObject();
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			try {
				String key = entry.getKey();
				Object value = entry.getValue();
				// 空值转换成JSON的空对象
				if (value == null) {
					value = JSONObject.NULL;
				} else if (value instanceof PersistentSet) {
					PersistentSet set = (PersistentSet) value;
					value = ToJson(set);
				}
				// 如果是$type$，表示实体类型，转换成EntityType
				if (key.equals("$type$")) {
					json.put("EntityType", value);
				} else if (value instanceof Date) {
					Date d1 = (Date) value;
					Calendar c = Calendar.getInstance();
					long time = d1.getTime() + c.get(Calendar.ZONE_OFFSET);
					json.put(key, time);
				} else if (value instanceof HashMap) {
					JSONObject json1 = MapToJson((Map<String, Object>) value);
					json.put(key, json1);
				} else {
					json.put(key, value);
				}
			} catch (JSONException e) {
				throw new WebApplicationException(400);
			}
		}
		return json;
	}

	// 把集合转换成Json数组
	private Object ToJson(PersistentSet set) {
		// 没加载的集合当做空
		if (!set.wasInitialized()) {
			return JSONObject.NULL;
		}
		JSONArray array = new JSONArray();
		for (Object obj : set) {
			Map<String, Object> map = (Map<String, Object>) obj;
			JSONObject json = MapToJson(map);
			array.put(json);
		}
		return array;
	}

	@GET
	@Path("{hql}/{sumNames}")
	@Produces(MediaType.APPLICATION_JSON)
	// 求总数
	public JSONObject querySum(@PathParam("hql") String query,
			@PathParam("sumNames") String names) {
		JSONObject result = new JSONObject();
		// 组织sums串
		String sums = "";
		for (String name : names.split(",")) {
			if (!sums.equals(""))
				sums += ",";
			sums += "sum(" + name + ") as " + name;
		}
		String hql = "";
		if (!sums.equals("")) {
			hql = "select new map(count(*) as Count, " + sums + ") " + query;
		} else {
			hql = "select new map(count(*) as Count) " + query;
		}
		// hql = "select new map(count(*) as Count, " + sums + ") " + query;
		List<Object> l = this.hibernateTemplate.find(hql);
		// 把map转换成json对象
		Map<String, Object> map = (Map<String, Object>) l.get(0);
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			try {
				result.put(entry.getKey(), entry.getValue());
			} catch (JSONException e) {
				throw new WebApplicationException(400);
			}
		}
		return result;
	}

	@GET
	@Path("sql/{sql}")
	@Produces(MediaType.APPLICATION_JSON)
	// 获取一页数据
	public JSONArray sqlQuery(@PathParam("sql") String query) {
		JSONArray array = new JSONArray();
		// sql中有除号的时候替换
		query = query.replace("|", "/");
		final String sql = query;
		List list = (List) hibernateTemplate.execute(new HibernateCallback() {
			public Object doInHibernate(Session session)
					throws HibernateException {
				return session.createSQLQuery(sql).list();
			}
		});
		for (Object obj : list) {
			// 把单个map转换成JSON对象
			Object[] c = (Object[]) obj;
			JSONObject json = new JSONObject();
			for (int i = 0; i < c.length; i++) {
				try {
					json.put("col" + i, c[i]);
				} catch (JSONException e) {
					throw new WebApplicationException(400);
				}
			}
			array.put(json);
		}
		return array;
	}

	/*
	 * 执行一批对象操作，包括保存，删除，hql语句，hql批量对象语句等。用json串表示。 json串格式为 [一批语句]， 语句格式为
	 * {operator:'语句类型', entity:'实体类型', data:数据, name:前台配置的名字} 语句类型有 save 保存,
	 * delete 删除, hql 执行hql, hqlAll 对一批对象执行hql 实体类型 当执行hql语句的时候，可以没有 数据
	 * 保存为一般json串，删除时为id号，执行hql时为hql语句 执行批量hql时数据 {hql:'hql语句'，ids:['id',
	 * 'id']}，主要原因是如果id太多，不能一次执行完，目前先不做
	 */
	@POST
	public JSONObject excute(String values) {
		log.debug(values);
		try {
			// 返回后台计算的结果,格式为 {对象名:{对象值}}
			JSONObject result = new JSONObject();
			// 一条条执行
			JSONArray array = new JSONArray(values);
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.getJSONObject(i);
				String oper = object.getString("operator");
				final String data = object.getString("data");
				// 保存
				if (oper.equals("save")) {
					JSONObject obj = save(object.getString("entity"), object
							.getJSONObject("data"));
					result.put(object.getString("name"), obj);
				} else if (oper.equals("delete")) {
					// 删除
					delete(object.getString("entity"), object.getInt("data"));
				} else if (oper.equals("hql")) {
					// 执行hql
					this.hibernateTemplate.bulkUpdate(object.getString("data"));
				} else if (oper.equals("sql")) {
					hibernateTemplate.execute(new HibernateCallback() {
						public Object doInHibernate(Session session)
								throws HibernateException {
							return session.createSQLQuery(data).executeUpdate();
						}
					});
				} else if (oper.equals("hqlAll")) {
					// 执行批量hql
					throw new NotImplementedException();
				} else {
					throw new WebApplicationException(500);
				}
			}
			return result;
		} catch (JSONException e) {
			throw new WebApplicationException(500);
		}
	}

	@POST
	@Path("{entity}")
	public String save(@PathParam("entity") String entityName, String values) {
		log.debug("entity:" + entityName + ", values:" + values);
		try {
			JSONObject object = new JSONObject(values);
			save(entityName, object);
		} catch (JSONException e) {
			throw new WebApplicationException(500);
		}
		return "ok";
	}

	// 内部保存过程，name为界面上传过来的要对象名字，返回的是后台表达式计算后的对象内容
	private JSONObject save(String entityName, JSONObject object)
			throws JSONException {
		// 根据实体名字去除配置属性信息
		ClassMetadata classData = this.hibernateTemplate.getSessionFactory()
				.getClassMetadata(entityName);
		JSONObject result = new JSONObject();
		// 把json对象转换成map
		Map<String, Object> map = new HashMap<String, Object>();
		Iterator<String> iter = object.keys();
		while (iter.hasNext()) {
			String key = iter.next();
			Object value = object.get(key);
			Type propType = null;
			try {
				propType = classData.getPropertyType(key);
			} catch (Exception e) {

			}

			if (object.isNull(key)) {
				// 空的id号不往属性里放，以便按新增处理
				if (!key.equals("id")) {
					map.put(key, null);
				}
			} else if (value instanceof JSONArray) {
				// Json数组转换成一对多关系的Set
				Set<Map<String, Object>> set = saveSet((JSONArray) value);
				map.put(key, set);
			} else if (value instanceof JSONObject) {
				JSONObject obj = (JSONObject) value;
				String type = (String) obj.get("EntityType");
				Map<String, Object> set = saveWithoutExp(type,
						(JSONObject) value);
				map.put(key, set);
			} else if (propType != null
					&& (propType instanceof DateType || propType instanceof TimeType)) {
				long l = 0;
				if (value instanceof Double) {
					l = ((Double) value).longValue();
				} else if (value instanceof Long) {
					l = ((Long) value).longValue();
				} else if (value instanceof Integer) {
					l = ((Integer) value).intValue();
				}
				Date d = new Date(l);
				map.put(key, d);
			} else if (value instanceof Integer
					&& propType instanceof DoubleType) {
				// int直接转换成double
				Integer v = (Integer) value;
				map.put(key, v.doubleValue());
			} else if (value instanceof Integer && propType instanceof LongType) {
				Long v = Long.valueOf(value.toString());
				map.put(key, v.longValue());
			} else {
				// 有需要后台计算的表达式，后台计算后，把结果返回
				if (value instanceof String
						&& value.toString().indexOf("#") != -1) {
					// 调用表达式运算
					try {
						value = ExpressionGenerator.getExpressionValue(value
								.toString());
					} catch (Exception e) {
						log.debug(value + "表达式处理发生异常,使用self值");
					}
					result.put(key, value);
				}
				map.put(key, value);
			}
		}
		this.hibernateTemplate.saveOrUpdate(entityName, map);
		result.put("id", map.get("id"));
		return result;
	}

	// 保存JsonObject，并转换为Map，保存时，不做后台表达式运算，用于一对多关系中的子的保存
	private Map<String, Object> saveWithoutExp(String entityName,
			JSONObject object) throws JSONException {
		// 根据实体名字去除配置属性信息
		ClassMetadata classData = this.hibernateTemplate.getSessionFactory()
				.getClassMetadata(entityName);
		// 把json对象转换成map
		Map<String, Object> map = new HashMap<String, Object>();
		Iterator<String> iter = object.keys();
		while (iter.hasNext()) {
			String key = iter.next();
			Type propType = null;
			try {
				propType = classData.getPropertyType(key);
			} catch (Exception e) {

			}

			Object value = object.get(key);
			if (object.isNull(key)) {
				// 空的id号不往属性里放，以便按新增处理
				if (!key.equals("id")) {
					map.put(key, null);
				}
			} else if (value instanceof JSONArray) {
				// Json数组转换成一对多关系的Set
				Set<Map<String, Object>> set = saveSet((JSONArray) value);
				map.put(key, set);
			} else if (propType != null
					&& (propType instanceof DateType || propType instanceof TimeType)) {
				long l = 0;
				if (value instanceof Double) {
					l = ((Double) value).longValue();
				} else if (value instanceof Long) {
					l = ((Long) value).longValue();
				}
				Date d = new Date(l);
				map.put(key, d);
			} else if (value instanceof Integer
					&& propType instanceof DoubleType) {
				// int直接转换成double
				Integer v = (Integer) value;
				map.put(key, v.doubleValue());
			} else if (value instanceof JSONObject) {
				JSONObject obj = (JSONObject) value;
				String type = (String) obj.get("EntityType");
				Map<String, Object> set = saveWithoutExp(type,
						(JSONObject) value);
				map.put(key, set);
			} else {
				map.put(key, value);
			}
		}
		this.hibernateTemplate.saveOrUpdate(entityName, map);
		return map;
	}

	// 保存JSONArray里的对象，并转换为Set
	private Set<Map<String, Object>> saveSet(JSONArray array)
			throws JSONException {
		Set<Map<String, Object>> set = new HashSet<Map<String, Object>>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject obj = (JSONObject) array.get(i);
			String type = (String) obj.get("EntityType");
			Map<String, Object> map = saveWithoutExp(type, obj);
			set.add(map);
		}
		return set;
	}
	
	@GET
	@Path("/one/{hql}/{attrname}")
	@Produces(MediaType.APPLICATION_JSON)
	public JSONObject queryOne(@PathParam("hql") String query, @PathParam("attrname") String names) {
		log.debug(query);
		JSONObject result = new JSONObject();
		List<Object> list = this.hibernateTemplate.find(query);
		if (list.size() != 1) {
			// 查询到多条数据，抛出异常
			throw new WebApplicationException(500);
		}
		// 对象数组的第一个为返回的对象，后面的内容为对象计算结果
		String[] snames = names.split(",");
		Object[] objs = (Object[])list.get(0);
		Map<String, Object> map = (Map<String, Object>) objs[0];
		for(int i = 1; i < objs.length; i++)
		{
			map.put(snames[i - 1], objs[i]);
		}
		result = (JSONObject) new JsonTransfer().MapToJson(map);
		log.debug(result.toString());
		return result;
	}

	@POST
	@Path("hql/{pageIndex}/{pageSize}")
	@Produces(MediaType.APPLICATION_JSON)
	// 获取一页数据
	public JSONArray postQuery(@PathParam("pageSize") int pageSize,
			@PathParam("pageIndex") int pageIndex, String query) {
		JSONArray array = new JSONArray();
		log.debug(query + ", size=" + pageSize + ", index=" + pageIndex);
		//如果pageIndex小于0，直接返回
		if(pageIndex < 0) {
			return array;
		}
		List list = this.hibernateTemplate.executeFind(new HibernateCall(query,
				pageIndex, pageSize));
		for (Object obj : list) {
			// 把单个map转换成JSON对象
			Map<String, Object> map = (Map<String, Object>) obj;
			JSONObject json = (JSONObject) new JsonTransfer().MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}

	@GET
	@Path("{hql}/{attrname}/{pageIndex}/{pageSize}")
	@Produces(MediaType.APPLICATION_JSON)
	// 获取一页数据，可以带计算字段
	public JSONArray query(@PathParam("hql") String query,
			@PathParam("attrname") String names,
			@PathParam("pageSize") int pageSize,
			@PathParam("pageIndex") int pageIndex) {
		JSONArray array = new JSONArray();
		log.debug(query + ", names=" + names + ", size=" + pageSize + ", index=" + pageIndex);
		//如果pageIndex小于0，直接返回
		if(pageIndex < 0) {
			return array;
		}
		List list = this.hibernateTemplate.executeFind(new HibernateCall(query,
				pageIndex, pageSize));
		for (Object obj : list) {
			//属性名为每一个计算项对应的名称
			String[] snames = names.split(",");
			//select多项时，后面的各项为第一项对象的计算属性
			Object[] objs = (Object[])obj;
			Map<String, Object> map = (Map<String, Object>) objs[0];
			for(int i = 1; i < objs.length; i++)
			{
				map.put(snames[i - 1], objs[i]);
			}
			JSONObject json = (JSONObject) new JsonTransfer().MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}

	@POST
	@Path("hql/{sumNames}")
	@Produces(MediaType.APPLICATION_JSON)
	// 求总数
	public JSONObject postQuerySum(@PathParam("sumNames") String names, String query) {
		JSONObject result = new JSONObject();
		// 组织sums串
		String sums = "";
		for (String name : names.split(",")) {
			if (!sums.equals(""))
				sums += ",";
			sums += "sum(" + name + ") as " + name;
		}
	    String hql="";
	    if(!sums.equals(""))
	    {
	    	 hql = "select new map(count(*) as Count, " + sums + ") " + query;
	    }
	    else
	    {
	    	 hql = "select new map(count(*) as Count) " + query;
	    }
	    log.debug(hql);
	    List<Object> l = this.hibernateTemplate.find(hql);
		// 把map转换成json对象
		Map<String, Object> map = (Map<String, Object>) l.get(0);
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			try {
				result.put(entry.getKey(), entry.getValue());
			} catch (JSONException e) {
				throw new WebApplicationException(400);
			}
		}
		log.debug(result);
		return result;
	}

	@POST
	@Path("sql/{sumNames}")
	// 求总数
	public JSONObject postSQLSum(@PathParam("sumNames") String names, String query) {
		// 组织sums串
		String sums = "";
		String[] snames = names.split(",");
		for (String name : snames) {
			if (!sums.equals(""))
				sums += ",";
			sums += "sum(" + name + ") as " + name;
		}
	    String sql="";
	    if(!sums.equals(""))
	    {
	    	//前面多添加一个count，以便统一返回数组，而不是单个对象
	    	sql = "select count(*) as Count, count(*) as Count, " + sums + " from (" + query + ") t";
	    }
	    else
	    {
	    	//前面多添加一个count，以便统一返回数组，而不是单个对象
	    	sql = "select count(*) as Count, count(*) as Count " + " from (" + query + ") t";
	    }
	    log.debug(sql);
	    HibernateSQL call = new HibernateSQL(sql);
		List list = (List) hibernateTemplate.execute(call);
		// 把map转换成json对象
		Object[] objs = (Object[])list.get(0);
		Map<String, Object> map = new HashMap<String, Object>();
		// 先把Count放进去，头一个抛弃掉
		map.put("Count", objs[1]);
		for(int i = 2; i < objs.length; i++)
		{
			map.put(snames[i - 2], objs[i]);
		}
		JSONObject result = (JSONObject) new JsonTransfer().MapToJson(map);
		log.debug(result);
		return result;
	}

	@POST
	@Path("sql/{pageIndex}/{pageSize}")
	// 按sql方式执行后，获取一页数据，字段名由SQL语句决定
	public JSONArray postSQLPage(
			@PathParam("pageSize") int pageSize,
			@PathParam("pageIndex") int pageIndex,
			String query) {
		JSONArray array = new JSONArray();
		log.debug(query + ", size=" + pageSize + ", index=" + pageIndex);
		//如果pageIndex小于0，直接返回
		if(pageIndex < 0) {
			return array;
		}
		HibernateSQLCall sqlCall = new HibernateSQLCall(query, pageIndex, pageSize);
		sqlCall.transformer = Transformers.ALIAS_TO_ENTITY_MAP;
		List<Map<String, Object>> list = this.hibernateTemplate.executeFind(sqlCall);
		for (Map<String, Object> map : list) {
			JSONObject json = (JSONObject) new JsonTransfer().MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}
	
	@POST
	@Path("sql/{names}/{pageIndex}/{pageSize}")
	// 按sql方式执行后，获取一页数据，字段名由前台给定
	public JSONArray postSQLPage(
			@PathParam("names") String names,
			@PathParam("pageSize") int pageSize,
			@PathParam("pageIndex") int pageIndex,
			String query) {
		JSONArray array = new JSONArray();
		log.debug(query + ", size=" + pageSize + ", index=" + pageIndex);
		//如果pageIndex小于0，直接返回
		if(pageIndex < 0) {
			return array;
		}
		HibernateSQLCall sqlCall = new HibernateSQLCall(query, pageIndex, pageSize);
		List list = this.hibernateTemplate.executeFind(sqlCall);
		for (Object obj : list) {
			//属性名sql语句执行后的别名
			String[] snames = names.split(",");
			//把sql语句结果转换成map
			Object[] objs = (Object[])obj;
			Map<String, Object> map = new HashMap<String, Object>();
			for(int i = 0; i < objs.length; i++)
			{
				map.put(snames[i], objs[i]);
			}
			JSONObject json = (JSONObject) new JsonTransfer().MapToJson(map);
			array.put(json);
		}
		log.debug(array.toString());
		return array;
	}

	// 不带分页的执行sql查询
	class HibernateSQL implements HibernateCallback {
		String sql;

		public HibernateSQL(String sql) {
			this.sql = sql;
		}
		
		public Object doInHibernate(Session session) {
			Query q = session.createSQLQuery(sql);
			List result = q.list();
			return result;
		}
	}
	
	// 执行sql分页查询，结果集形式可以设置
	class HibernateSQLCall implements HibernateCallback {
		String sql;
		int page;
		int rows;
		//查询结果转换器，可以转换成Map等。
		public ResultTransformer transformer = null;
		
		public HibernateSQLCall(String sql, int page, int rows) {
			this.sql = sql;
			this.page = page;
			this.rows = rows;
		}

		public Object doInHibernate(Session session) {
			Query q = session.createSQLQuery(sql);
			//有转换器，设置转换器
			if(transformer != null) {
				q.setResultTransformer(transformer);
			}
			List result = q.setFirstResult(page * rows).setMaxResults(rows).list();
			return result;
		}
	}

	// 转换器，在转换期间会检查对象是否已经转换过，避免重新转换，产生死循环
	class JsonTransfer {
		// 保存已经转换过的对象
		private List<Map<String, Object>> transed = new ArrayList<Map<String, Object>>();

		// 把单个map转换成JSON对象
		public Object MapToJson(Map<String, Object> map) {
			// 转换过，返回空对象
			if (contains(map))
				return JSONObject.NULL;
			transed.add(map);
			JSONObject json = new JSONObject();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				try {
					String key = entry.getKey();
					Object value = entry.getValue();
					// 空值转换成JSON的空对象
					if (value == null) {
						value = JSONObject.NULL;
					}  else if (value instanceof HashMap) {
						value = MapToJson((Map<String, Object>) value);
					} else if (value instanceof PersistentSet) {
						PersistentSet set = (PersistentSet) value;
						value = ToJson(set);
					}
					// 如果是$type$，表示实体类型，转换成EntityType
					if (key.equals("$type$")) {
						json.put("EntityType", value);
					} else if (value instanceof Date) {
						Date d1 = (Date) value;
						Calendar c = Calendar.getInstance();
						long time = d1.getTime() + c.get(Calendar.ZONE_OFFSET);
						json.put(key, time);
					} else if (value instanceof MapProxy) {
						// MapProxy没有加载，不管
					} else {
						json.put(key, value);
					}
				} catch (JSONException e) {
					throw new WebApplicationException(400);
				}
			}
			return json;
		}

		// 把集合转换成Json数组
		public Object ToJson(PersistentSet set) {
			// 没加载的集合当做空
			if (!set.wasInitialized()) {
				return JSONObject.NULL;
			}
			JSONArray array = new JSONArray();
			for (Object obj : set) {
				Map<String, Object> map = (Map<String, Object>) obj;
				JSONObject json = (JSONObject) MapToJson(map);
				array.put(json);
			}
			return array;
		}
		
		//判断已经转换过的内容里是否包含给定对象
		public boolean contains(Map<String, Object> obj) {
			for(Map<String, Object> map : this.transed) {
				if(obj == map) {
					return true;
				}
			}
			return false;
		}
	}

	@POST
	@Path("{entity}/{id}")
	public String delete(@PathParam("entity") String entityName,
			@PathParam("id") int id) {
		String hql = "delete from " + entityName + " where id=" + id;
		log.debug(hql);
		this.hibernateTemplate.bulkUpdate(hql);
		return "ok";
	}

	// 保存文件
	@SuppressWarnings("finally")
	@Path("savefile")
	@POST
	public String savefile(byte[] file,
			@QueryParam("FileName") String filename,
			@QueryParam("BlobId") String blob_id,
			@QueryParam("EntityName") String EntityName) {
		String result = null;
		Map<String, Object> map = new HashMap<String, Object>();
		try {
			map.put("filename", filename);
			map.put("id", blob_id);
			map.put("blob", Hibernate.createBlob(file));
			this.hibernateTemplate.saveOrUpdate(EntityName, map);
			this.hibernateTemplate.flush();
			result = "";
		} catch (Exception e) {
			throw new WebApplicationException(500);
		} finally {
			return result;
		}
	}

	// 获得图片
	@Path("file/{blobid}")
	public String getimage(@Context HttpServletResponse response,
			@PathParam("blobid") String blobid) {
		try {
			List list = this.hibernateTemplate.find("from t_blob where id='"
					+ blobid + "'");
			if (list.size() == 0)
				return "";
			Map map = (Map) list.get(0);
			// 获得文件名
			String filename = (String) map.get("filename");
			filename = URLEncoder.encode(filename, "UTF-8");
			// 获得文件
			Blob file = (Blob) map.get("blob");
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition", "attachment;filename=\""
					+ filename + "\"");
			// 把文件的内容送入响应流中
			InputStream is = file.getBinaryStream();
			OutputStream os = new BufferedOutputStream(response
					.getOutputStream());
			transformStream(is, os);
			is.close();
			os.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}

	// 导excel
	@GET
	@Path("excel/{hql}/{count}/{cols}")
	// 获取一页数据
	public String exporttoexcel(@Context HttpServletRequest request,
			@Context HttpServletResponse response,
			@PathParam("hql") String query, @PathParam("count") int count,
			@PathParam("cols") String cols) {
		try {
			HSSFWorkbook workbook = new HSSFWorkbook();
			HSSFSheet sheet = workbook.createSheet();
			HSSFFont font = workbook.createFont();
			font.setColor((short) HSSFFont.COLOR_NORMAL);
			font.setBoldweight((short) HSSFFont.BOLDWEIGHT_BOLD);
			// 设置格式
			HSSFCellStyle cellStyle = workbook.createCellStyle();
			cellStyle.setAlignment((short) HSSFCellStyle.ALIGN_CENTER);
			cellStyle.setFont(font);
			HSSFRow row = null;
			HSSFCell cell = null;
			// 创建第0行 标题
			int rowNum = 0;
			row = sheet.createRow((short) rowNum);
			String[] colsStr = cols.split("\\|");
			for (int titleCol = 0; titleCol < colsStr.length; titleCol++) {
				cell = row.createCell((short) (titleCol));
				// 设置列类型
				cell.setCellType(HSSFCell.CELL_TYPE_STRING);
				// 设置列的字符集为中文
				cell.setEncoding((short) HSSFCell.ENCODING_UTF_16);
				// 设置内容
				String[] names = colsStr[titleCol].split(":");
				// 有别名,设置内容为别名，否则，为字段名。
				if(names.length > 1) {
					cell.setCellValue(names[1]);
				} else {
					cell.setCellValue(colsStr[titleCol]);
				}
				cell.setCellStyle(cellStyle);
			}

			// 处理文件数据
			int pageSize = 20;
			int pageCount = count % pageSize == 0 ? (count / pageSize)
					: (count / pageSize) + 1;
			for (int i = 0; i <= pageCount; i++) {
				List list = null;
				//以sql:开始，说明是执行sql语句，否则，执行hql语句
				if(query.startsWith("sql:")) {
					String sql = query.substring(4);
					List objList = this.hibernateTemplate
						.executeFind(new HibernateSQLCall(sql, i, pageSize));
					list = new ArrayList();
					//将sql语句的结果转换成map
					for (Object obj : objList) {
						//把sql语句结果转换成map
						Object[] objs = (Object[])obj;
						Map<String, Object> map = new HashMap<String, Object>();
						for(int j = 0; j < objs.length; j++)
						{
							map.put("col" + j, objs[j]);
						}
						list.add(map);
					}
				} else {
					list = this.hibernateTemplate
						.executeFind(new HibernateCall(query, i, pageSize));
				}
				for (int j = 0; j < list.size(); j++) {
					Object obj = list.get(j);
					// 把单个map转换成JSON对象
					Map<String, Object> map = (Map<String, Object>) obj;
					JSONObject json = MapToJson(map);
					rowNum++;
					row = sheet.createRow((short) rowNum);
					for (int z = 0; z < colsStr.length; z++) {
						// 得到数据
						String[] names = colsStr[z].split(":");
						// 有别名，字段名为第一项，否则，整个是字段名
						String colName = colsStr[z];
						if(names.length > 1) {
							colName = names[0];
						}
						String data = "";
						if (map.get(colName) != null) {
							data = map.get(colName).toString();
						}
						cell = row.createCell((short) (z));
						// 设置列类型
						cell.setCellType(HSSFCell.CELL_TYPE_STRING);
						// 设置列的字符集为中文
						cell.setEncoding((short) HSSFCell.ENCODING_UTF_16);
						cell.setCellValue(data);
					}
				}
			}
			// 产生临时文件
			File file = File.createTempFile("temp", ".xls");
			FileOutputStream fileOutputStream = new FileOutputStream(file);
			// 写文件
			workbook.write(fileOutputStream);
			// 清缓存
			response.setStatus(HttpServletResponse.SC_OK);
			response
					.setContentType("application/octet-stream;charset=\"gb2312\"");
			response.addHeader("Content-Disposition", "attachment;filename=\" "
					+ file.getName() + "\"");
			// 把文件的内容送入响应流中
			byte[] b = new byte[1024];
			BufferedInputStream bis = new BufferedInputStream(
					new FileInputStream(file));
			BufferedOutputStream bos = new BufferedOutputStream(response
					.getOutputStream());
			while (bis.read(b) != -1) {
				bos.write(b);
			}
			bis.close();
			file.deleteOnExit();
			bos.close();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return "";
	}

	@GET
	@Path("fapiao/{hql}/{count}")
	// 获取一页数据
	public String fapiaotoxml(@Context HttpServletRequest request,
			@Context HttpServletResponse response,
			@PathParam("hql") String query, @PathParam("count") int count) {
		try {
			query = query.replaceAll("\\^", "<");
			// 生成xml
			Document document = DocumentHelper.createDocument();
			Element root = document.addElement("wlfpML",
					"http://www.12366.ha.cn");
			root.addAttribute("cnName", "通用模板报文");
			root.addAttribute("name", "String");
			root.addAttribute("version", "wlfp2010");
			root.addAttribute("schemaLocation",
					"http://www.12366.ha.cn wlfpML.xsd");
			root.addAttribute("xmlns:xsi",
					"http://www.w3.org/2001/XMLSchema-instance");
			Element tax = root.addElement("TAXPAYER", "http://www.12366.ha.cn"
					+ " ");
			tax.addAttribute("NSRMC", "巩义市燃气有限公司");
			tax.addAttribute("NSRSBH", "410181712641199");
			tax.addAttribute("RECORDCOUNT", count + "");
			Element xsRecords = root.addElement("xsRecords");
			// 开始结束票号
			String stratPiaoHao = "";
			String endPiaoHao = "";
			String fapiaodam = "";
			// 处理文件数据
			int pageSize = 20;
			int pageCount = count % pageSize == 0 ? (count / pageSize)
					: (count / pageSize) + 1;
			for (int i = 0; i <= pageCount; i++) {
				List list = this.hibernateTemplate
						.executeFind(new HibernateCall(query, i, pageSize));
				for (int j = 0; j < list.size(); j++) {
					Object obj = list.get(j);

					// 把单个map转换成JSON对象
					Map<String, Object> map = (Map<String, Object>) obj;
					JSONObject json = MapToJson(map);
					// 添加单个发票元素
					Element record = xsRecords.addElement("xsRecord");
					Element head = record.addElement("xsHead");
					// 发票代码
					String fpdm = getJsonAttr(json, "f_invoiceid");
					Element fpdmElem = head.addElement("FPDM");
					fpdmElem.setText(fpdm);
					if (i == 0 && j == 0) {
						fapiaodam = fpdm;
					}
					// 发票号码
					String fphm = getJsonAttr(json, "f_invoicenum");
					Element fphmElem = head.addElement("FPHM");
					fphmElem.setText(fphm);
					if (i == 0 && j == 0) {
						stratPiaoHao = fphm;
					}
					endPiaoHao = fphm;
					// 开票日期3
					Element kprqElem = head.addElement("KPRQ");
					Date kpDate = (Date) map.get("f_fapiaodate");
					if (kpDate != null) {
						SimpleDateFormat sdf = new SimpleDateFormat(
								"yyyy-MM-dd");
						String d = sdf.format(kpDate);
						kprqElem.setText(d);
					} else {
						kprqElem.setText("");
					}
					// 发票金额
					String fpje = getJsonAttr(json, "f_money");
					Element fpjeElem = head.addElement("FPJE");
					fpjeElem.setText(fpje);
					// 用户编号
					String yhbh = getJsonAttr(json, "f_userid");
					Element yhbhElem = head.addElement("FKDWSBH");
					yhbhElem.setText(yhbh);
					// 单位名称
					String dwmc = getJsonAttr(json, "f_username");
					Element dwmcElem = head.addElement("FKDWMC");
					dwmcElem.setText(dwmc);
					// 发票状态
					String fpzt = getJsonAttr(json, "f_fapiaostatue");
					if (fpzt.equals("已用")) {
						fpzt = "10";
					} else if (fpzt.equals("作废")) {
						fpzt = "20";
					} else if (fpzt.equals("空白作废")) {
						fpzt = "30";
					}
					Element fpztElem = head.addElement("FPZT_DM");
					fpztElem.setText(fpzt);

					String fpztzt = getJsonAttr(json, "f_fapiaostatue");
					if (fpztzt.indexOf("作废") != -1) {
						// 作废日期3
						Element ZFRQElem = head.addElement("ZFRQ");
						Date zfDate = (Date) map.get("f_fapiaodate");
						if (zfDate != null) {
							SimpleDateFormat sdf = new SimpleDateFormat(
									"yyyy-MM-dd");
							String d = sdf.format(zfDate);
							ZFRQElem.setText(d);
						} else {
							ZFRQElem.setText("");
						}
					} else {
						Element ZFRQElem = head.addElement("ZFRQ ");
					}

					Element TPDMElem = head.addElement("TPDM ");
					Element TPHMElem = head.addElement("TPHM ");

					Element xsMXElem = record.addElement("xsMX");
					// <xh> 型号
					Element xhElem = xsMXElem.addElement("XH");
					xhElem.setText("1");
					// 商品名称
					Element mcElem = xsMXElem.addElement("SPMC");
					mcElem.setText("煤制气");
					// 单位
					Element dwElem = xsMXElem.addElement("SPDW");
					dwElem.setText("方");
					// 单价
					String dj = getJsonAttr(json, "f_gasprice");
					// 数量
					String num = getJsonAttr(json, "f_gas");
					// 单价=金额/数量 保留两位
					dj = String.format("%.2f", Double.parseDouble(fpje)
							/ Double.parseDouble(num));
					if (Double.parseDouble(fpje) == 0) {
						dj = "0.0";
					}
					Element djElem = xsMXElem.addElement("SPDJ");
					djElem.setText(dj);
					Element slElem = xsMXElem.addElement("SPSL");
					slElem.setText(num);
					// 金额
					Element jeElem = xsMXElem.addElement("SPJE");
					jeElem.setText(fpje);

				}
			}
			// 返回
			String path = request.getSession().getServletContext().getRealPath(
					"");
			// String uuid = UUID.randomUUID().toString();
			String fname = "WLFP_TY_410181712641199_" + fapiaodam + "_"
					+ stratPiaoHao + "_" + endPiaoHao;
			path = path + "\\" + fname + ".xml";
			OutputFormat format = OutputFormat.createPrettyPrint();
			format.setEncoding("UTF-8");
			XMLWriter xmlWriter = new XMLWriter(new FileOutputStream(path),
					format);
			xmlWriter.write(document);
			xmlWriter.close();

			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition", "attachment;filename=\""
					+ fname + ".xml" + "\"");
			// 把文件的内容送入响应流中
			File file = new File(path);
			InputStream is = new FileInputStream(file);
			OutputStream os = new BufferedOutputStream(response
					.getOutputStream());
			transformStream(is, os);
			is.close();
			os.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "";
	}

	public String getJsonAttr(JSONObject json, String attr) {
		try {

			if (!json.has(attr)) {
				return "";
			}
			Object obj = json.get(attr);
			if (obj == null) {
				return "";
			}
			return obj.toString();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	public void transformStream(InputStream is, OutputStream os) {
		try {
			byte[] buffer = new byte[1024];
			// 读取的实际长度
			int length = is.read(buffer);
			while (length != -1) {
				os.write(buffer, 0, length);
				length = is.read(buffer);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
