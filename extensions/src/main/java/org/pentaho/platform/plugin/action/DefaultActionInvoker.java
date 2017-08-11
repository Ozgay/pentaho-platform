/*!
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright (c) 2017 Pentaho Corporation..  All rights reserved.
 */

package org.pentaho.platform.plugin.action;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.action.ActionInvocationException;
import org.pentaho.platform.api.action.IAction;
import org.pentaho.platform.api.action.IActionDetails;
import org.pentaho.platform.api.action.IActionInvokeStatus;
import org.pentaho.platform.api.action.IActionInvoker;
import org.pentaho.platform.api.scheduler2.IBackgroundExecutionStreamProvider;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.plugin.action.messages.Messages;
import org.pentaho.platform.util.ActionUtil;
import org.pentaho.platform.util.StringUtil;
import org.pentaho.platform.util.messages.LocaleHelper;
import org.pentaho.platform.workitem.WorkItemLifecyclePhase;
import org.pentaho.platform.workitem.WorkItemLifecyclePublisher;

import java.io.Serializable;
import java.util.Map;

/**
 * A concrete implementation of the {@link IActionInvoker} interface that invokes the {@link IAction} locally.
 */
public class DefaultActionInvoker extends AbstractActionInvoker {

  private static final Log logger = LogFactory.getLog( DefaultActionInvoker.class );

  /**
   * Invokes the provided {@link IAction} locally as the provided {@code actionUser}.
   *
   * @param actionDetails The {@link IActionDetails} representing the {@link IAction} to be invoked
   * @return the {@link IActionInvokeStatus} object containing information about the action invocation
   * @throws Exception when the {@code IAction} cannot be invoked for some reason.
   */
  protected IActionInvokeStatus invokeActionImpl( final IActionDetails actionDetails ) throws Exception {
    final String workItemUid = ActionUtil.extractUid( actionDetails );

    if ( actionDetails == null || actionDetails.getAction() == null || actionDetails.getParameters() == null ) {
      final String failureMessage = Messages.getInstance().getCantInvokeNullAction();
      WorkItemLifecyclePublisher.publish( workItemUid, actionDetails.getParameters(), WorkItemLifecyclePhase.FAILED,
        failureMessage );
      throw new ActionInvocationException( failureMessage );
    }

    final Map<String, Serializable> params = actionDetails.getParameters();
    WorkItemLifecyclePublisher.publish( workItemUid, params, WorkItemLifecyclePhase.IN_PROGRESS );

    final IAction actionBean = actionDetails.getAction();
    if ( logger.isDebugEnabled() ) {
      logger.debug( Messages.getInstance().getRunningInBackgroundLocally( actionBean.getClass().getName(), params ) );
    }

    // set the locale, if not already set
    if ( params.get( LocaleHelper.USER_LOCALE_PARAM ) == null || StringUtils.isEmpty(
      params.get( LocaleHelper.USER_LOCALE_PARAM ).toString() ) ) {
      params.put( LocaleHelper.USER_LOCALE_PARAM, LocaleHelper.getLocale() );
    }

    // remove the scheduling infrastructure properties
    params.remove( ActionUtil.INVOKER_ACTIONCLASS );
    params.remove( ActionUtil.INVOKER_ACTIONID );
    params.remove( ActionUtil.INVOKER_ACTIONUSER );
    // build the stream provider
    final IBackgroundExecutionStreamProvider streamProvider = getStreamProvider( params );
    params.remove( ActionUtil.INVOKER_STREAMPROVIDER );
    params.remove( ActionUtil.INVOKER_UIPASSPARAM );

    final String actionUser = actionDetails.getUserName();
    final ActionRunner actionBeanRunner = new ActionRunner( actionBean, actionUser, params, streamProvider );
    final ActionInvokeStatus status = new ActionInvokeStatus();

    boolean requiresUpdate = false;
    try {
      if ( ( StringUtil.isEmpty( actionUser ) ) || ( actionUser.equals( "system session" ) ) ) { //$NON-NLS-1$
        // For now, don't try to run quartz jobs as authenticated if the user
        // that created the job is a system user. See PPP-2350
        requiresUpdate = SecurityHelper.getInstance().runAsAnonymous( actionBeanRunner );
      } else {
        requiresUpdate = SecurityHelper.getInstance().runAsUser( actionUser, actionBeanRunner );
      }
    } catch ( final Throwable t ) {
      WorkItemLifecyclePublisher.publish( workItemUid, params, WorkItemLifecyclePhase.FAILED, t.toString() );
      status.setThrowable( t );
    }
    status.setRequiresUpdate( requiresUpdate );

    return status;
  }
}
