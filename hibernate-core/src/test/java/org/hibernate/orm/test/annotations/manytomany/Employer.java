/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */

//$Id$
package org.hibernate.orm.test.annotations.manytomany;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.OrderBy;
import javax.persistence.Table;

import org.hibernate.annotations.Cascade;

/**
 * Employer in a employer-Employee relationship
 *
 * @author Emmanuel Bernard
 */
@Entity()
@Table(name="`Employer`")
@SuppressWarnings({"serial", "unchecked"})
public class Employer implements Serializable {
	private Integer id;
	private Collection employees;
	private List contractors;

	@ManyToMany(
			targetEntity = Contractor.class,
			cascade = {CascadeType.PERSIST, CascadeType.MERGE}
	)
	@JoinTable(
			name = "EMPLOYER_CONTRACTOR",
			joinColumns = {@JoinColumn(name = "EMPLOYER_ID")},
			inverseJoinColumns = {@JoinColumn(name = "CONTRACTOR_ID")}
	)
	@Cascade(org.hibernate.annotations.CascadeType.SAVE_UPDATE)
	@OrderBy("name desc")	
	public List getContractors() {
		return contractors;
	}

	public void setContractors(List contractors) {
		this.contractors = contractors;
	}

	@ManyToMany(
			targetEntity = Employee.class,
			cascade = {CascadeType.PERSIST, CascadeType.MERGE}
	)
	@JoinTable(
			name = "EMPLOYER_EMPLOYEE",
			joinColumns = {@JoinColumn(name = "EMPER_ID")},
			inverseJoinColumns = {@JoinColumn(name = "EMPEE_ID")}
	)
	@Cascade(org.hibernate.annotations.CascadeType.SAVE_UPDATE)
	@OrderBy("name asc")
	public Collection getEmployees() {
		return employees;
	}

	@Id
	@GeneratedValue
	public Integer getId() {
		return id;
	}

	public void setEmployees(Collection set) {
		employees = set;
	}

	public void setId(Integer integer) {
		id = integer;
	}
}