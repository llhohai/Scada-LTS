/*
 * (c) 2016 Abil'I.T. http://abilit.eu/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.scada_lts.mango.service;

import com.serotonin.db.IntValuePair;
import com.serotonin.mango.db.dao.PointValueDao;
import com.serotonin.mango.vo.DataPointExtendedNameComparator;
import com.serotonin.mango.vo.DataPointVO;
import com.serotonin.mango.vo.User;
import com.serotonin.mango.vo.bean.PointHistoryCount;
import com.serotonin.mango.vo.event.PointEventDetectorVO;
import com.serotonin.mango.vo.hierarchy.PointFolder;
import com.serotonin.mango.vo.hierarchy.PointHierarchy;
import com.serotonin.mango.vo.permission.DataPointAccess;
import com.serotonin.util.Tuple;
import org.quartz.SchedulerException;
import org.scada_lts.cache.EventDetectorsCache;
import org.scada_lts.config.ScadaConfig;
import org.scada_lts.dao.*;
import org.scada_lts.mango.adapter.MangoDataPoint;
import org.scada_lts.mango.adapter.MangoPointHierarchy;
import org.scada_lts.service.PointHierarchyService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Service for DataPointDAO
 *
 * @author Mateusz Kaproń Abil'I.T. development team, sdt@abilit.eu
 */

@Service
public class DataPointService implements MangoDataPoint {

	//TODO
//	@Resource
	private static final DataPointDAO dataPointDAO = new DataPointDAO();

	//TODO
//	@Autowired
	private static final UserCommentDAO userCommentDAO = new UserCommentDAO();

	//TODO
//	@Autowired
	private static final PointEventDetectorDAO pointEventDetectorDAO = new PointEventDetectorDAO();

	//TODO
//	@Autowired
	private static final PointHierarchyDAO pointHierarchyDAO = new PointHierarchyDAO();

	//TODO
//	@Autowired
	private static final DataPointUserDAO dataPointUserDAO = new DataPointUserDAO();

	private static final PointHierarchyService pointHierarchyService = new PointHierarchyService();

	@Override
	public String generateUniqueXid() {
		return DAO.getInstance().generateUniqueXid(DataPointVO.XID_PREFIX, "dataPoints");
	}

	@Override
	public boolean isXidUnique(String xid, int excludeId) {
		return DAO.getInstance().isXidUnique(xid, excludeId, "dataPoints");
	}

	@Override
	public String getExtendedPointName(int dataPointId) {
		DataPointVO dataPoint = getDataPoint(dataPointId);
		if (dataPoint == null) {
			return "?";
		}
		return dataPoint.getExtendedName();
	}

	@Override
	public DataPointVO getDataPoint(String xId) {
		DataPointVO dp = dataPointDAO.getDataPoint(xId);
		setRelationalData(dp);
		return dp;
	}

	@Override
	public DataPointVO getDataPoint(int id) {
		DataPointVO dp = dataPointDAO.getDataPoint(id);
		setRelationalData(dp);
		return dp;
	}

	@Override
	public List<DataPointVO> getDataPoints(Comparator<DataPointVO> comparator, boolean includeRelationalData) {
		List<DataPointVO> dpList = dataPointDAO.getDataPoints();
		if (includeRelationalData) {
			setRelationalData(dpList);
		}
		if (comparator != null) {
			Collections.sort(dpList, comparator);
		}
		return dpList;
	}

	@Override
	public List<DataPointVO> getDataPoints(int dataSourceId, Comparator<DataPointVO> comparator) {
		List<DataPointVO> dpList = dataPointDAO.getDataPoints(dataSourceId);
		setRelationalData(dpList);
		if (comparator != null) {
			Collections.sort(dpList, comparator);
		}
		return dpList;
	}

	private void setRelationalData(List<DataPointVO> dpList) {
		for (DataPointVO dp: dpList) {
			setRelationalData(dp);
		}
	}

	private void setRelationalData(DataPointVO dp) {
		if (dp != null) {
			setEventDetectors(dp);
			setPointComments(dp);
		}
	}

	@Override
	public void saveDataPoint(final DataPointVO dp) {
		if (dp.getId() == -1) {
			insertDataPoint(dp);
			PointHierarchyDAO.cachedPointHierarchy = null;
			MangoPointHierarchy.getInst().addDataPoint(dp);
		} else {
			updateDataPoint(dp);
			MangoPointHierarchy.getInst().updateDataPoint(dp);
		}
	}

	@Override
	public void insertDataPoint(final DataPointVO dp) {
		//Create default text renderer
		if (dp.getTextRenderer() == null) {
			dp.defaultTextRenderer();
		}

		dataPointDAO.insert(dp);
		saveEventDetectors(dp);
	}

	@Override
	public void updateDataPoint(final DataPointVO dp) {
		DataPointVO oldDp = dataPointDAO.getDataPoint(dp.getId());
		if (oldDp.getPointLocator().getDataTypeId() != dp.getPointLocator().getDataTypeId()) {
			new PointValueDao().deletePointValuesWithMismatchedType(dp.getId(), dp.getPointLocator().getDataTypeId());
		}

		updateDataPointShallow(dp);
		saveEventDetectors(dp);
	}

	@Override
	public void updateDataPointShallow(final DataPointVO dp) {
		dataPointDAO.update(dp);
	}

	//TODO PointValueDAO
	@Override
	public void deleteDataPoint(int dataPointId) {
		DataPointVO dp = getDataPoint(dataPointId);
		if (dp != null) {

		}
	}

	//TODO PointValueDAO
	@Override
	public void deleteDataPoints(int dataSourceId) {
		List<DataPointVO> oldList = getDataPoints(dataSourceId, null);

		for (DataPointVO dp: oldList) {
			beforePointDelete(dp.getId());
		}

		for (DataPointVO dp: oldList) {
			deletePointHistory(dp.getId());
		}

		List<Integer> pointIds = dataPointDAO.getDataPointsIds(dataSourceId);
		if (pointIds.size() > 0) {
			StringBuilder idsWithCommaSB = new StringBuilder();
			Iterator idsIterator = pointIds.iterator();
			idsWithCommaSB.append(pointIds.get(0));
			while (idsIterator.hasNext()) {
				idsWithCommaSB.append(",");
				idsWithCommaSB.append(idsIterator.next());
			}

			deleteDataPointImpl(idsWithCommaSB.toString());
		}

	}

	//TODO PointValueDAO
	private void beforePointDelete(int dpId) {

	}

	//TODO PointValueDAO
	@Override
	public void deletePointHistory(int dpId) {

	}

	//TODO PointValueDAO
	@Override
	public void deletePointHistory(int dpId, long min, long max) {

	}

	@Override
	public void deleteDataPointImpl(String dataPointIds) {

		//TODO delete eventHandler
		userCommentDAO.deleteUserCommentPoint(dataPointIds);
		pointEventDetectorDAO.deleteWithId(dataPointIds);
		//TODO delete dataPointUsers
		//TODO delete watchList
		dataPointDAO.deleteWithIn(dataPointIds);

		PointHierarchyDAO.cachedPointHierarchy = null;
		MangoPointHierarchy.getInst().deleteDataPoint(dataPointIds);
	}

	@Override
	public int getDataPointIdFromDetectorId(int pointEventDetectorId) {
		return pointEventDetectorDAO.getDataPointId(pointEventDetectorId);
	}

	@Override
	public String getDetectorXid(int pointEventDetectorId) {
		return pointEventDetectorDAO.getXid(pointEventDetectorId);
	}

	@Override
	public int getDetectorId(String pointEventDetectorXid, int dataPointId) {
		return pointEventDetectorDAO.getId(pointEventDetectorXid, dataPointId);
	}

	@Override
	public String generateEventDetectorUniqueXid(int dataPointId) {
		String xid = DAO.getInstance().generateXid(PointEventDetectorVO.XID_PREFIX);
		while (!isEventDetectorXidUnique(dataPointId, xid, -1)) {
			xid = DAO.getInstance().generateXid(PointEventDetectorVO.XID_PREFIX);
		}
		return xid;
	}

	@Override
	public boolean isEventDetectorXidUnique(int dataPointId, String xid, int excludeId) {
		return pointEventDetectorDAO.isEventDetectorXidUnique(dataPointId, xid, excludeId);
	}

	private void setEventDetectors(DataPointVO dataPoint) {
		dataPoint.setEventDetectors(getEventDetectors(dataPoint));
	}

	private List<PointEventDetectorVO> getEventDetectors(DataPointVO dataPoint) {

		EventDetectorsCache.LOG.trace("getEventDetectors() dpId:" + dataPoint.getId());
		long startTime = 0;
		if (EventDetectorsCache.LOG.isTraceEnabled()) {
			startTime = System.currentTimeMillis();
		}

		List<PointEventDetectorVO> result = null;
		try {
			boolean cacheEnable = ScadaConfig.getInstance().getBoolean(ScadaConfig.ENABLE_CACHE, false);
			if (cacheEnable) {
				result = EventDetectorsCache.getInstance().getEventDetectors(dataPoint);
			} else {
				result = pointEventDetectorDAO.getPointEventDetectors(dataPoint);
			}
		} catch (SchedulerException | IOException e) {
			EventDetectorsCache.LOG.error(e);
		}

		long endTime = 0;
		if (EventDetectorsCache.LOG.isTraceEnabled()) {
			endTime = System.currentTimeMillis();
		}
		EventDetectorsCache.LOG.trace("TimeExecute:"+(endTime-startTime)+ " getEventDetectors() dpId:"+dataPoint.getId());

		return result;
	}

	private void saveEventDetectors(DataPointVO dataPoint) {
		List<PointEventDetectorVO> detectors = getEventDetectors(dataPoint);

		for (PointEventDetectorVO pointEventDetector: dataPoint.getEventDetectors()) {
			if (pointEventDetector.getId() < 0) {
				pointEventDetectorDAO.insert(pointEventDetector);
			} else {
				pointEventDetectorDAO.update(pointEventDetector);
			}
		}

		for (PointEventDetectorVO pointEventDetector: detectors) {
			//TODO delete eventHandlers
			pointEventDetectorDAO.delete(pointEventDetector.getId());
		}
	}

	private PointEventDetectorVO removeFromList(List<PointEventDetectorVO> list, int id) {
		for (PointEventDetectorVO ped : list) {
			if (ped.getId() == id) {
				list.remove(ped);
				return ped;
			}
		}
		return null;
	}

	@Override
	public void copyPermissions(final int fromDataPointId, final int toDataPointId) {

		final List<Tuple<Integer,Integer>> ups = dataPointUserDAO.getDataPointUsers(fromDataPointId);
		dataPointUserDAO.insert(ups, toDataPointId);
	}

	private void setPointComments(DataPointVO dp) {
		dp.setComments(userCommentDAO.getPointComments(dp));
	}

	@Override
	public void savePointsInFolder(PointFolder folder) {

		// Save the points in the subfolders
		for (PointFolder sf : folder.getSubfolders()) {
			savePointsInFolder(sf);
		}

		// Update the folder references in the points.
		DataPointVO dp;
		for (IntValuePair p : folder.getPoints()) {
			dp = getDataPoint(p.getKey());
			// The point may have been deleted while editing the hierarchy.
			if (dp != null) {
				dp.setPointFolderId(folder.getId());
				dataPointDAO.update(dp);
			}
		}
	}

	@Override
	public PointHierarchy getPointHierarchy() {
		if (PointHierarchyDAO.cachedPointHierarchy == null) {
			final Map<Integer, List<PointFolder>> folders = pointHierarchyDAO.getFolderList();

			PointHierarchy ph = new PointHierarchy();
			PointHierarchyService phService = new PointHierarchyService();
			phService.addFoldersToHierarchy(ph, 0 ,folders);

			List<DataPointVO> points = getDataPoints(DataPointExtendedNameComparator.instance, false);
			for (DataPointVO dataPoint: points) {
				ph.addDataPoint(dataPoint.getId(), dataPoint.getPointFolderId(), dataPoint.getExtendedName());
			}

			PointHierarchyDAO.cachedPointHierarchy = ph;
		}
		return PointHierarchyDAO.cachedPointHierarchy;
	}

	@Override
	public void savePointHierarchy(PointFolder root) {
		pointHierarchyService.savePointHierarchy(root);
	}

	@Override
	public void savePointFolder(PointFolder folder, int parentId) {
		pointHierarchyService.savePointFolder(folder, parentId);
	}

	//TODO PointValueDAO
	public List<PointHistoryCount> getTopPointHistoryCounts() {
		return null;
	}

	public List<DataPointAccess> getDataPointAccessList(final int userId) {
		return dataPointUserDAO.getDataPointAccessList(userId);
	}

	public void deleteDataPointUser(int userId) {
		dataPointUserDAO.delete(userId);
	}

	public void insertPermissions(User user) {
		dataPointUserDAO.insertPermissions(user);
	}

}
